package com.flowdeployer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FlowDeployerApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlowDeployerApplication.class, args);
    }
}
