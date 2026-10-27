package com.bz.va;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VaApplication {

    public static void main(String[] args) {
        SpringApplication.run(VaApplication.class, args);
    }
}
