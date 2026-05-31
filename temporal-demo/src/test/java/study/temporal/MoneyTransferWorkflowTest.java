package study.temporal;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;
import study.temporal.activity.AccountActivitiesImpl;
import study.temporal.workflow.MoneyTransferWorkflow;
import study.temporal.workflow.MoneyTransferWorkflowImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用内存测试环境跑通整个转账 —— 不需要任何外部服务、不需要 Docker。
 *
 * 这个环境的妙处:
 *   1) 它是一个完整的「迷你 Temporal Server」,跑在内存里。
 *   2) 时间是「虚拟」的(time-skipping):deposit 重试之间的退避等待会被瞬间跳过,
 *      所以即便配了指数退避,测试也毫秒级跑完。
 *
 * 运行:mvn -q test
 */
public class MoneyTransferWorkflowTest {

    private static final String TASK_QUEUE = "money-transfer";

    @Test
    void 转账成功_并自动重试deposit到成功() {
        // 1) 启动内存测试环境
        TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance();
        try {
            // 2) 注册 Workflow 实现与 Activity 实现到同一个 task queue
            Worker worker = testEnv.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(MoneyTransferWorkflowImpl.class);
            AccountActivitiesImpl activities = new AccountActivitiesImpl();
            worker.registerActivitiesImplementations(activities);
            testEnv.start();

            // 3) 像调本地方法一样发起一次持久化执行
            WorkflowClient client = testEnv.getWorkflowClient();
            MoneyTransferWorkflow workflow = client.newWorkflowStub(
                    MoneyTransferWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());

            String result = workflow.transfer("alice", "bob", 100);

            // 4) 断言:结果正确,且余额变化只发生一次(没有因重试而重复扣款)
            assertTrue(result.startsWith("OK"), result);
            assertEquals(900, activities.balances().get("alice"));  // 1000 - 100
            assertEquals(100, activities.balances().get("bob"));    // 0 + 100,deposit 重试了 3 次但只入账一次
        } finally {
            testEnv.close();
        }
    }
}
