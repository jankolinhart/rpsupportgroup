package com.reelypops.rpsupportgroup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RpSupportGroupApplication {
    public static void main(String[] args) {
        SpringApplication.run(RpSupportGroupApplication.class, args);
    }
}
