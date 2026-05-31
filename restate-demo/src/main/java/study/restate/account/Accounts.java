package study.restate.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模拟账户系统(内存)。这些是「有副作用」的操作 —— 在 Restate 里它们必须被
 * ctx.run(...) 包裹,这样结果才会写进 durable 日志,崩溃恢复时不会重复执行。
 */
public final class Accounts {

    private static final Logger log = LoggerFactory.getLogger(Accounts.class);

    private static final Map<String, Integer> balances = new ConcurrentHashMap<>(Map.of(
            "alice", 1000,
            "bob", 0
    ));

    /** 模拟 deposit 前两次失败、第三次成功,用来演示 Restate 的自动重试。 */
    private static final AtomicInteger depositAttempts = new AtomicInteger(0);

    private Accounts() {}

    public static void withdraw(String account, int amount) {
        balances.merge(account, -amount, Integer::sum);
        Ledger.record("withdraw " + account + " " + amount);  // 跨进程账本(崩溃恢复证据)
        log.info("[副作用] withdraw {} from {} -> 余额 {}", amount, account, balances.get(account));
    }

    public static void deposit(String account, int amount) {
        int attempt = depositAttempts.incrementAndGet();
        if (attempt < 3) {
            log.warn("[副作用] deposit 第 {} 次失败(模拟临时故障)", attempt);
            throw new RuntimeException("银行暂时不可用,稍后重试");
        }
        balances.merge(account, amount, Integer::sum);
        Ledger.record("deposit " + account + " " + amount);  // 跨进程账本
        log.info("[副作用] deposit {} to {} -> 余额 {}(第 {} 次成功)", amount, account, balances.get(account), attempt);
    }

    public static void refund(String account, int amount) {
        balances.merge(account, amount, Integer::sum);
        log.info("[副作用] refund(补偿)退回 {} 给 {} -> 余额 {}", amount, account, balances.get(account));
    }

    public static void notify(String account, String message) {
        log.info("[副作用] notify {} : {}", account, message);
    }
}
