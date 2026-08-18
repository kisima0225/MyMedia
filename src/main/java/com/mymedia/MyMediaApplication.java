package com.mymedia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MyMediaApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyMediaApplication.class, args);
    }
}
