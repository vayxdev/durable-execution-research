package study.restate.service;

import dev.restate.sdk.Context;
import dev.restate.sdk.annotation.Handler;
import dev.restate.sdk.annotation.Service;
import dev.restate.sdk.springboot.RestateComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import study.restate.account.Accounts;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Restate 版转账,对照 Temporal 版来看核心设计差异。
 *
 * ┌─ Restate 的执行模型(和 Temporal 对照)────────────────────────────────┐
 * │ 1) 你的服务就是个普通 HTTP 服务,把自己「注册」到 Restate Server。       │
 * │ 2) 客户端不直接调你,而是调 Restate Server;Server 作为「持久化代理」     │
 * │    把请求转发给你的 handler,并把每个 ctx.* 操作的结果记进 durable log。  │
 * │ 3) 进程崩溃后,Server 重新把请求投给(可能是另一个)实例,SDK 按日志      │
 * │    «续跑»:已经 journal 过的步骤直接返回旧结果,没跑过的才真正执行。      │
 * │                                                                        │
 * │ 与 Temporal 的关键区别:                                                │
 * │   - 没有「确定性重放整段代码」的心智负担。普通代码即可,只需把副作用      │
 * │     用 ctx.run(...) 包起来。                                            │
 * │   - 不需要区分 Workflow / Activity 两种角色,一个 handler 走到底。       │
 * └────────────────────────────────────────────────────────────────────────┘
 */
@Service              // Restate 服务(定义 handler)
@RestateComponent     // 注册为 Spring bean 并被 starter 收集绑定到 endpoint(自带 @Component)
public class MoneyTransferService {

    private static final Logger log = LoggerFactory.getLogger(MoneyTransferService.class);

    /**
     * 一次转账。Context 是 Restate 注入的「持久化上下文」,所有需要崩溃可恢复的
     * 操作都要经过它。
     */
    @Handler
    public String transfer(Context ctx, TransferRequest req) {
        log.info("开始转账:{} -> {},金额 {}", req.from(), req.to(), req.amount());

        // 手写一个 Saga 补偿栈(Restate 没有内置 Saga,但用普通代码就能实现 —— 这正是
        // 「普通代码 + durable steps」模型的好处:控制流就是你熟悉的 try/catch)
        Deque<Runnable> compensations = new ArrayDeque<>();
        try {
            // 1) 扣款:用 ctx.run 包裹副作用 -> 结果写入 durable log,恢复时不重复扣
            ctx.run("withdraw", () -> Accounts.withdraw(req.from(), req.amount()));
            compensations.push(() -> ctx.run("refund", () -> Accounts.refund(req.from(), req.amount())));

            // ★ 崩溃演示窗口:已扣款,进入一段「持久化定时器」暂停。
            //   ctx.sleep 是 durable 的 —— 计时由 Restate Server 维护,SDK 在此处会 suspend
            //   (服务进程甚至可以完全释放)。此刻杀掉服务进程,Server 到点会重新投递调用,
            //   SDK 按 journal 续跑:withdraw 已记录 -> 直接短路不重执行,从这里往后接着跑。
            log.info("已扣款,进入 10s 持久化暂停 —— 现在可以杀掉服务进程");
            ctx.sleep(Duration.ofSeconds(10));

            // 2) 入账:ctx.run 内抛异常时,Restate 会按重试策略自动重试这一步
            ctx.run("deposit", () -> Accounts.deposit(req.to(), req.amount()));

            // 3) 通知:失败也不回滚 —— 单独 try 掉
            try {
                ctx.run("notify", () -> Accounts.notify(req.to(), "你收到一笔转账:" + req.amount()));
            } catch (Exception e) {
                log.warn("通知失败,忽略", e);
            }

            log.info("转账完成");
            return "OK: " + req.from() + " -> " + req.to() + " : " + req.amount();
        } catch (Exception e) {
            log.error("转账失败,执行补偿回滚", e);
            while (!compensations.isEmpty()) {
                compensations.pop().run();
            }
            throw e;
        }
    }
}
