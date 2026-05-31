package study.temporal.api;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.web.bind.annotation.*;
import study.temporal.workflow.MoneyTransferWorkflow;

import java.util.Map;

/**
 * REST 入口,替代原来的 TransferStarter main。
 * WorkflowClient 由 temporal-spring-boot-starter 自动装配,直接注入即可。
 */
@RestController
@RequestMapping("/transfer")
public class TransferController {

    static final String TASK_QUEUE = "money-transfer";

    private final WorkflowClient client;

    public TransferController(WorkflowClient client) {
        this.client = client;
    }

    /** 异步发起一次转账,立即返回 workflowId(不阻塞等那 10s 持久化暂停)。 */
    @PostMapping
    public Map<String, String> start(@RequestBody TransferRequest req) {
        String workflowId = "transfer-" + req.from() + "-" + req.to() + "-" + System.currentTimeMillis();
        MoneyTransferWorkflow workflow = client.newWorkflowStub(
                MoneyTransferWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TASK_QUEUE)
                        .setWorkflowId(workflowId)
                        .build());
        WorkflowClient.start(workflow::transfer, req.from(), req.to(), req.amount());
        return Map.of("workflowId", workflowId, "status", "STARTED");
    }

    /** 按 workflowId 取结果(workflow 未完成会阻塞到完成)。 */
    @GetMapping("/{workflowId}")
    public Map<String, String> result(@PathVariable String workflowId) {
        String result = client.newUntypedWorkflowStub(workflowId).getResult(String.class);
        return Map.of("workflowId", workflowId, "result", result);
    }
}
