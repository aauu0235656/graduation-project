package com.party.regulation;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@MapperScan("com.party.regulation.mapper")
@EnableAsync//启动异步调用的能力
public class RegulationQaApplication {

    public static void main(String[] args) {
        SpringApplication.run(RegulationQaApplication.class, args);
    }
}
