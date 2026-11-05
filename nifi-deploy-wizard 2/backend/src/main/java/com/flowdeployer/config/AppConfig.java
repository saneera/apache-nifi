package com.flowdeployer.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    /**
     * Virtual threads for deployments and SSE streams — both are long-running, blocking I/O.
     * Deployments run here, not on the request thread, so they keep going if the browser goes away.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService deployExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
