package com.party.regulation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
//配置（API Key、模型、温度等）
@Data
@Component
@ConfigurationProperties(prefix = "llm")//@ConfigurationProperties 绑定
public class LlmProperties {
    private String apiKey;
    private String apiUrl;
    private String model;
    private String embeddingUrl;
    private String embeddingModel;
    private Double temperature = 0.7;
    private Integer maxTokens = 2000;
    private Float vectorThreshold = 0.3f;
}
