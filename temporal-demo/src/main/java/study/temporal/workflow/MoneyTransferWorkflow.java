package study.temporal.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow 接口 = 「业务编排」。
 *
 * @WorkflowMethod 标记的方法是入口。Client 调用它来启动一次持久化执行,
 * 这次执行的全过程(每个 Activity 调用、定时器、信号)都会被记成事件历史。
 */
@WorkflowInterface
public interface MoneyTransferWorkflow {

    @WorkflowMethod
    String transfer(String fromAccount, String toAccount, int amount);
}
