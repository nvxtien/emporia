package com.emporia.authorisation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthorisationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthorisationServiceApplication.class, args);
    }
}
