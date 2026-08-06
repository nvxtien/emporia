package com.emporia.ordermanagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
@EnableKafka
@EnableScheduling
@SpringBootApplication
public class OrderManagementServiceApplication {
    public static void main(String[] args) { SpringApplication.run(OrderManagementServiceApplication.class, args); }
}
