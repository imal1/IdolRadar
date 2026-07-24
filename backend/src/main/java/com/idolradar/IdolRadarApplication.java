package com.idolradar;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idolradar.seed.SeedService;
import com.idolradar.worker.WorkerModels;
import com.idolradar.worker.WorkerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * 统一启动入口：同一镜像通过 {@code APP_MODE} 运行 API、迁移、种子数据或单次 Worker。
 * 命令模式不会启动 Web 容器，便于部署系统按任务退出码判断成功与否。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IdolRadarApplication {

    private static final Logger log = LoggerFactory.getLogger(IdolRadarApplication.class);

    public static void main(String[] args) {
        String mode = resolveMode(args);
        SpringApplication application = new SpringApplication(IdolRadarApplication.class);
        // 虚拟线程用于大量阻塞式 HTTP/数据库 I/O；不改变业务并发与限流规则。
        application.setDefaultProperties(Map.of("spring.threads.virtual.enabled", "true"));
        // 必须在 Spring 条件装配前写入最终模式，否则可能装配错误的 API/Worker Bean。
        application.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("idolradarResolvedMode", Map.of("app.mode", mode))));
        if (!"api".equals(mode)) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }

        ConfigurableApplicationContext context = application.run(args);
        // application.run 返回表示 Spring 上下文已完成初始化；启动失败时不会误报成功。
        log.info("IdolRadar 启动成功，运行模式：{}", mode);
        if ("api".equals(mode)) {
            // API 生命周期交给 Spring Web 容器管理；命令模式则继续执行并主动退出。
            return;
        }

        int exitCode = 0;
        try {
            if ("seed".equals(mode)) {
                SeedService.SeedResult result = context.getBean(SeedService.class).seed();
                printJson(context, Map.of("event", "seed_completed", "result", result));
            } else if ("worker".equals(mode)) {
                WorkerModels.WorkerRunResult result = context.getBean(WorkerService.class).runOnce();
                printJson(context, Map.of("event", "worker_completed", "result", result));
            }
        } catch (RuntimeException error) {
            exitCode = 1;
            // HTTP 客户端异常 cause 可能包含带凭据的 URI，禁止直接输出异常链。
            System.err.println("IdolRadar command failed: " + error.getClass().getSimpleName());
        } finally {
            exitCode = Math.max(exitCode, SpringApplication.exit(context));
        }
        System.exit(exitCode);
    }

    private static void printJson(ConfigurableApplicationContext context, Map<String, Object> payload) {
        try {
            // 机器可读日志方便容器平台采集单次任务结果。
            System.out.println(context.getBean(ObjectMapper.class).writeValueAsString(payload));
        } catch (JsonProcessingException error) {
            System.out.println("{\"event\":\"command_completed\",\"serialization\":\"failed\"}");
        }
    }

    private static String resolveMode(String[] args) {
        // 优先级：命令行参数 > JVM 系统属性 > 环境变量 > api 默认值。
        String mode = System.getProperty(
                "app.mode",
                System.getenv().getOrDefault("APP_MODE", "api")).trim().toLowerCase();
        for (String argument : args) {
            if (argument.startsWith("--app.mode=")) {
                mode = argument.substring("--app.mode=".length()).trim().toLowerCase();
            }
        }
        if (!java.util.Set.of("api", "migrate", "seed", "worker").contains(mode)) {
            throw new IllegalArgumentException("APP_MODE must be api, migrate, seed, or worker");
        }
        return mode;
    }
}
