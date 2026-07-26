package com.emporia.ordermanagement;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
class KafkaTopicsConfig {
    @Bean
    NewTopic orderCommandsTopic(@Value("${emporia.kafka.commands-topic}") String name) {
        return TopicBuilder.name(name).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic orderResultsTopic(@Value("${emporia.kafka.results-topic}") String name) {
        return TopicBuilder.name(name).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic ordersTopic(@Value("${emporia.kafka.orders-topic}") String name) {
        return TopicBuilder.name(name).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic executionCommandsTopic(@Value("${emporia.kafka.executions-topic}") String name) {
        return TopicBuilder.name(name).partitions(6).replicas(1).build();
    }
}
