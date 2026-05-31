package study.temporal.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Activity = 「有副作用的步骤」。
 *
 * 设计原理:Workflow 代码必须是确定性的(会被反复重放),所以任何与外界打交道、
 * 结果不可预测的操作(数据库、HTTP、读时钟、随机数)都必须放进 Activity。
 *
 * Activity 不参与重放:它执行一次后,结果被记进事件历史;Workflow 重放时不会真的
 * 再调一次,而是直接从历史里读回上次的返回值。这就是「每步只执行一次」的来源。
 */
@ActivityInterface
public interface AccountActivities {

    /** 从 from 账户扣款。 */
    @ActivityMethod
    void withdraw(String fromAccount, int amount);

    /** 向 to 账户入账。演示:会模拟前几次临时失败,触发 Temporal 自动重试。 */
    @ActivityMethod
    void deposit(String toAccount, int amount);

    /** 补偿操作:把钱退回 from 账户(Saga 回滚用)。 */
    @ActivityMethod
    void refund(String fromAccount, int amount);

    /** 发通知。演示:即使失败也不该让整个转账回滚。 */
    @ActivityMethod
    void notify(String account, String message);
}
