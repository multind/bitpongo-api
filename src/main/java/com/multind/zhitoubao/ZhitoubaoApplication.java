package com.multind.zhitoubao;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ZhitoubaoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZhitoubaoApplication.class, args);
    }
}
