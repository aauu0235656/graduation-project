package com.party.regulation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
//配置（分块大小、重叠数、索引路径）
@Data
@Component
@ConfigurationProperties(prefix = "index")
public class IndexProperties {
    private Integer chunkSize = 500;
    private Integer chunkOverlap = 100;
    private String lucenePath = "./data/lucene";
    private String faissPath = "./data/faiss";
}
