package com.ziwei.zw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 知微 · 智能体对话平台 启动类。
 *
 * <p>依赖 spring-ai-agentx-core / -rag（只用 jar，不改框架），
 * 业务骨架参考 aicube-robot 的知识库与对话模块。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ZwApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZwApplication.class, args);
    }
}
