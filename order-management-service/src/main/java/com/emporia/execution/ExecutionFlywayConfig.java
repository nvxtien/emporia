package com.emporia.execution;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class ExecutionFlywayConfig {

    @Bean(initMethod = "migrate")
    public Flyway executionFlyway(@Qualifier("executionDataSource") DataSource dataSource,
                                  @Value("${emporia.execution.datasource.hikari.schema:emporia_execution}") String schema) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations("classpath:db/migration/execution")
                .load();
    }
}
