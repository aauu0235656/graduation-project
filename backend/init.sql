-- 党内法规知识库问答系统 数据库初始化脚本
-- 数据库：party_regulation_db

CREATE DATABASE IF NOT EXISTS party_regulation_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE party_regulation_db;

-- 1. 法规文档表
DROP TABLE IF EXISTS regulation_document;
CREATE TABLE regulation_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(300) NOT NULL COMMENT '法规标题',
    category VARCHAR(50) COMMENT '法规类别',
    org VARCHAR(100) COMMENT '发布机关',
    publish_date DATE COMMENT '发布日期',
    status TINYINT DEFAULT 0 COMMENT '0待索引 1已索引 2索引失败',
    file_path VARCHAR(500) COMMENT '原始文件路径',
    char_count INT DEFAULT 0 COMMENT '字数统计',
    content LONGTEXT COMMENT '法规全文',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_category (category),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='法规文档表';

-- 2. 法规分块表
DROP TABLE IF EXISTS regulation_chunk;
CREATE TABLE regulation_chunk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_id BIGINT NOT NULL COMMENT '所属文档ID',
    chunk_index INT NOT NULL COMMENT '分块序号',
    content TEXT NOT NULL COMMENT '分块内容',
    char_count INT DEFAULT 0 COMMENT '分块字数',
    chapter VARCHAR(200) COMMENT '所属章节',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_doc_id (doc_id),
    CONSTRAINT fk_chunk_doc FOREIGN KEY (doc_id) REFERENCES regulation_document(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='法规分块表';

-- 3. 问答历史表
DROP TABLE IF EXISTS qa_history;
CREATE TABLE qa_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    question TEXT NOT NULL COMMENT '用户问题',
    answer LONGTEXT COMMENT '系统回答',
    sources TEXT COMMENT '参考来源JSON',
    model_name VARCHAR(100) COMMENT 'LLM模型名',
    search_strategy VARCHAR(50) COMMENT '检索策略',
    top_k INT DEFAULT 5 COMMENT '检索返回数量',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问答历史表';

-- 4. 系统配置表
DROP TABLE IF EXISTS system_config;
CREATE TABLE system_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(100) UNIQUE NOT NULL COMMENT '配置键',
    config_value TEXT COMMENT '配置值',
    description VARCHAR(300) COMMENT '配置说明',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置表';

-- 5. 插入默认配置（阿里云百炼）
INSERT INTO system_config (config_key, config_value, description) VALUES
('llm_api_key', 'sk-9e8bc7d269104ca1b92cbf6cb58a43ff', '阿里云百炼 API Key'),
('llm_api_url', 'https://dashscope.aliyuncs.com/compatible-mode/v1', '阿里云百炼 API 地址'),
('llm_model', 'qwen-plus', 'LLM 模型名称'),
('embedding_api_url', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 'Embedding API 地址'),
('embedding_model', 'text-embedding-v3', 'Embedding 模型名称（1024维）'),
('chunk_size', '500', '文本分块大小'),
('chunk_overlap', '100', '分块重叠大小'),
('search_top_k', '5', '检索返回数量'),
('temperature', '0.7', 'LLM 生成温度');
