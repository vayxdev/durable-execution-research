package study.temporal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 入口。启动后 temporal-spring-boot-starter 会:
 *   1) 自动配置 WorkflowClient(连 application.yml 里的 target);
 *   2) 扫描 study.temporal 包,把 @WorkflowImpl / @ActivityImpl 注册到 task queue;
 *   3) 启动 Worker 并常驻拉取任务。
 * 也就是说:不再需要手写 Worker / Starter 的 main 方法,应用一起来 Worker 就在跑。
 */
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
