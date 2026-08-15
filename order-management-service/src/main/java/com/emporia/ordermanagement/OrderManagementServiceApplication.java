package com.emporia.ordermanagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import org.springframework.cache.annotation.EnableCaching;

/**
 * {@code scanBasePackages} rather than a separate {@code @ComponentScan}, which
 * is what this used to carry.
 *
 * <p>{@code com.emporia.execution} and {@code com.emporia.ha} are siblings of
 * this package rather than sub-packages, so they have to be named. Naming them
 * on a standalone {@code @ComponentScan} silently dropped the exclude filters
 * {@code @SpringBootApplication} declares - a directly-placed annotation wins
 * over the same annotation reached through a meta-annotation, so the scan that
 * ran was the unfiltered one.
 *
 * <p>One of those filters is {@code TypeExcludeFilter}, which is how Spring's
 * test slices narrow a context. Without it {@code @DataJpaTest} stopped being a
 * persistence slice and tried to build the whole application, failing on the
 * first bean whose auto-configuration a slice does not include -
 * {@code RestClient.Builder}, then {@code ObjectMapper}, and onward. That took
 * the {@code postgres-it} specifications down with it, so the optimistic-lock
 * behaviour they cover went unverified. Importing those autoconfigurations one
 * at a time would only move the failure; the scan is what needed fixing.
 *
 * <p>{@code scanBasePackages} is an alias for the same attribute on the
 * {@code @ComponentScan} inside {@code @SpringBootApplication}, so the filters
 * come along with it.
 */
@EnableCaching
@EnableScheduling
@SpringBootApplication(scanBasePackages = {
        "com.emporia.ordermanagement",
        "com.emporia.execution",
        "com.emporia.ha"
})
public class OrderManagementServiceApplication {
    public static void main(String[] args) { SpringApplication.run(OrderManagementServiceApplication.class, args); }
}
