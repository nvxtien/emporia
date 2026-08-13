package com.emporia.ordermanagement.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Explicit Flyway bean for the primary (order-management) datasource.
 *
 * <p>Required because {@code ExecutionFlywayConfig} also declares a
 * {@code Flyway} bean: Spring Boot's auto-configured Flyway backs off once
 * any {@code Flyway} bean exists ({@code @ConditionalOnMissingBean(Flyway.class)}),
 * so without this, {@code emporia_order_data} silently stopped receiving new
 * migrations the moment the execution datasource was merged in - it never
 * failed, it just never ran again.
 */
@Configuration(proxyBeanMethods = false)
public class OmsFlywayConfig {

    @Bean(initMethod = "migrate")
    public Flyway omsFlyway(@Qualifier("dataSource") DataSource dataSource,
                            @Value("${spring.flyway.default-schema:emporia_order_data}") String schema) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations("classpath:db/migration/order_data")
                .load();
    }
}
