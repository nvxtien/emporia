package com.emporia.execution;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class ExecutionDataSourceConfig {

    @Bean(name = "executionDataSourceProperties")
    @ConfigurationProperties("emporia.execution.datasource")
    public DataSourceProperties executionDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "executionDataSource")
    @ConfigurationProperties("emporia.execution.datasource.hikari")
    public DataSource executionDataSource(
            @Qualifier("executionDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(name = "executionJdbcTemplate")
    public JdbcTemplate executionJdbcTemplate(
            @Qualifier("executionDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
