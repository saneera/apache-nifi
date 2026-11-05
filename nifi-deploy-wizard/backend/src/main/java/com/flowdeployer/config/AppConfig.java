package com.flowdeployer.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    /** In-cluster config via the pod's ServiceAccount; falls back to ~/.kube/config for local runs. */
    @Bean(destroyMethod = "close")
    public KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }

    /** One virtual thread per log stream — cheap, and log reading is blocking I/O. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService logStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
