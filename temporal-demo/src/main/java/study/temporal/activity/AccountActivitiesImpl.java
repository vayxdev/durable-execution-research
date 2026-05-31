package study.temporal.activity;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.spring.boot.ActivityImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Activity 实现 = 真正干活的地方,可以自由做任何副作用(这里用内存 Map 模拟账户余额)。
 *
 * 注意:Activity 实现里可以用随机数、时钟、IO —— 完全不受确定性约束。
 * 约束只针对 Workflow 代码。
 */
@Component
@ActivityImpl(taskQueues = "money-transfer")   // starter 把这个 bean 作为 Activity 注册
public class AccountActivitiesImpl implements AccountActivities {

    private static final Logger log = LoggerFactory.getLogger(AccountActivitiesImpl.class);

    /** 模拟账户余额表。 */
    private final Map<String, Integer> balances = new ConcurrentHashMap<>(Map.of(
            "alice", 1000,
            "bob", 0
    ));

    /** 用来模拟 deposit 前两次失败、第三次成功(展示自动重试)。 */
    private final AtomicInteger depositAttempts = new AtomicInteger(0);

    @Override
    public void withdraw(String fromAccount, int amount) {
        balances.merge(fromAccount, -amount, Integer::sum);
        Ledger.record("withdraw " + fromAccount + " " + amount);  // 记进磁盘账本(崩溃恢复的证据)
        log.info("[Activity] withdraw {} from {} -> 余额 {}", amount, fromAccount, balances.get(fromAccount));
    }

    @Override
    public void deposit(String toAccount, int amount) {
        ActivityExecutionContext ctx = Activity.getExecutionContext();
        int attempt = depositAttempts.incrementAndGet();
        // Temporal 通过 RetryOptions 自动重试,这里前两次抛异常模拟「对方银行临时不可用」
        if (attempt < 3) {
            log.warn("[Activity] deposit 第 {} 次尝试失败(模拟临时故障),attempt={} ",
                    ctx.getInfo().getAttempt(), attempt);
            throw Activity.wrap(new RuntimeException("银行暂时不可用,稍后重试"));
        }
        balances.merge(toAccount, amount, Integer::sum);
        Ledger.record("deposit " + toAccount + " " + amount);  // 记进磁盘账本
        log.info("[Activity] deposit {} to {} -> 余额 {}(第 {} 次尝试成功)",
                amount, toAccount, balances.get(toAccount), attempt);
    }

    @Override
    public void refund(String fromAccount, int amount) {
        balances.merge(fromAccount, amount, Integer::sum);
        log.info("[Activity] refund(补偿)退回 {} 给 {} -> 余额 {}", amount, fromAccount, balances.get(fromAccount));
    }

    @Override
    public void notify(String account, String message) {
        log.info("[Activity] notify {} : {}", account, message);
    }

    public Map<String, Integer> balances() {
        return balances;
    }
}
