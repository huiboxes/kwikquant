package com.kwikquant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class KwikquantApplication {

    public static void main(String[] args) {
        SpringApplication.run(KwikquantApplication.class, args);
    }
}
