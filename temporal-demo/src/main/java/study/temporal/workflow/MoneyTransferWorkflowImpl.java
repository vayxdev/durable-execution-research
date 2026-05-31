package study.temporal.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;
import study.temporal.activity.AccountActivities;

import java.time.Duration;

/**
 * Workflow 实现 = 编排逻辑,这段代码会被 Temporal「重放」。
 *
 * ┌─ 确定性约束(最重要的概念)──────────────────────────────────────────┐
 * │ 进程崩溃后,Temporal 会用事件历史「重放」这段代码来重建内存状态:        │
 * │ 重新从头执行,遇到已经记录过的 Activity 调用就直接返回历史里的结果,      │
 * │ 直到追上崩溃前的进度,再继续往后跑。                                    │
 * │                                                                      │
 * │ 因此这段代码必须每次重放都走完全相同的路径,禁止:                        │
 * │   - new Random() / UUID.randomUUID()  → 用 Workflow.newRandom()       │
 * │   - System.currentTimeMillis()        → 用 Workflow.currentTimeMillis │
 * │   - Thread.sleep()                    → 用 Workflow.sleep()           │
 * │   - 直接读数据库 / 调 HTTP            → 放进 Activity                   │
 * │   - new Thread()                       → 用 Workflow.newCancellation… │
 * └──────────────────────────────────────────────────────────────────────┘
 */
@WorkflowImpl(taskQueues = "money-transfer")   // starter 据此把本类注册到该 task queue
public class MoneyTransferWorkflowImpl implements MoneyTransferWorkflow {

    // Workflow.getLogger:重放期间会自动「静音」,避免同一条日志被打很多遍
    private static final Logger log = Workflow.getLogger(MoneyTransferWorkflowImpl.class);

    /**
     * 创建 Activity 的「存根」。调用存根的方法 = 让 Temporal 去调度一次 Activity 执行。
     * 这里配置了超时和重试策略,Temporal 会按策略自动重试失败的 Activity。
     */
    private final AccountActivities accounts = Workflow.newActivityStub(
            AccountActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(5))      // 单次执行超时
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofMillis(200))
                            .setBackoffCoefficient(2.0)                 // 指数退避
                            .setMaximumAttempts(5)                      // 最多重试 5 次
                            .build())
                    .build());

    @Override
    public String transfer(String fromAccount, String toAccount, int amount) {
        log.info("开始转账:{} -> {},金额 {}", fromAccount, toAccount, amount);

        // Saga:用于失败时按相反顺序执行补偿操作(典型的分布式事务回滚模式)
        Saga saga = new Saga(new Saga.Options.Builder().build());
        try {
            // 1) 扣款。成功后立刻登记补偿:万一后面失败,就退款。
            accounts.withdraw(fromAccount, amount);
            saga.addCompensation(() -> accounts.refund(fromAccount, amount));

            // ★ 崩溃演示窗口:已扣款,进入一段「持久化定时器」暂停。
            //   Workflow.sleep 不是 Thread.sleep —— 计时由 Server 维护,即使此刻把 Worker
            //   进程杀掉,定时器照走;Worker 重启后会从这里继续。是观察「断点恢复」的最佳时机。
            //   (在内存测试里 time-skipping 会瞬间跳过这 10 秒,不影响 mvn test 速度)
            log.info("已扣款,进入 10s 持久化暂停 —— 现在可以杀掉 Worker 进程");
            Workflow.sleep(Duration.ofSeconds(10));

            // 2) 入账。前两次会失败,Temporal 按上面的 RetryOptions 自动重试到成功。
            accounts.deposit(toAccount, amount);

            // 3) 通知。即便失败也不该回滚转账,所以单独用「不重试」的策略调用。
            try {
                accounts.notify(toAccount, "你收到一笔转账:" + amount);
            } catch (Exception e) {
                log.warn("通知失败,但转账已完成,忽略", e);
            }

            log.info("转账完成");
            return "OK: " + fromAccount + " -> " + toAccount + " : " + amount;
        } catch (Exception e) {
            // deposit 重试 5 次仍失败等场景 → 触发补偿,把扣的钱退回去
            log.error("转账失败,执行补偿回滚", e);
            saga.compensate();
            throw e;
        }
    }
}
