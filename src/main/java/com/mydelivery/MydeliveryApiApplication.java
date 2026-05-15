package com.mydelivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MydeliveryApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MydeliveryApiApplication.class, args);
    }
}

