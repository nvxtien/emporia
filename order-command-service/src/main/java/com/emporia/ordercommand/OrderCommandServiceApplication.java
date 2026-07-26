package com.emporia.ordercommand;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

@EnableKafka
@SpringBootApplication
public class OrderCommandServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderCommandServiceApplication.class, args);
    }
}
