package com.emporia.execution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(basePackages = {"com.emporia.execution", "com.emporia.ha"})
@EnableScheduling
public class ExecutionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExecutionServiceApplication.class, args);
    }
}
