package com.example.pupupudemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // 👈 加上这一行，开启定时任务功能
public class PupupuDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(PupupuDemoApplication.class, args);
    }

}
