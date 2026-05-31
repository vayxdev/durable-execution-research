package study.restate;

import dev.restate.sdk.springboot.EnableRestate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 入口。@EnableRestate 是总开关(@Import 了 Restate 的 endpoint 收集、
 * 内置 HTTP server、Client 三套自动配置)。
 *
 * 启动后:starter 通过 getBeansWithAnnotation(RestateComponent) 收集所有 @RestateComponent
 * bean,绑定到内置 endpoint(:9080)并启动 server —— 不再需要手写 RestateHttpServer.listen 的 main。
 */
@SpringBootApplication
@EnableRestate
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
