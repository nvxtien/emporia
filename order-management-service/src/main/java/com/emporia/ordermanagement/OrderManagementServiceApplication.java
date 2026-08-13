package com.emporia.ordermanagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
@EnableScheduling
@SpringBootApplication
@ComponentScan(basePackages = {
        "com.emporia.ordermanagement",
        "com.emporia.execution",
        "com.emporia.ha"
})
public class OrderManagementServiceApplication {
    public static void main(String[] args) { SpringApplication.run(OrderManagementServiceApplication.class, args); }
}
