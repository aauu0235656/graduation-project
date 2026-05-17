# 党内法规知识库问答系统 — 数据库设计文档

## 一、MySQL 数据库（主存储）

数据库名：`party_regulation_db`

### 1. 法规文档表 `regulation_document`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK AUTO_INCREMENT | 文档ID |
| title | VARCHAR(300) NOT NULL | 法规标题 |
| category | VARCHAR(50) | 法规类别（党章/纪律法规/监督法规/廉洁法规/干部管理/组织建设） |
| org | VARCHAR(100) | 发布机关 |
| publish_date | DATE | 发布日期 |
| status | TINYINT DEFAULT 0 | 状态：0待索引 1已索引 2索引失败 |
| file_path | VARCHAR(500) | 原始文件路径 |
| char_count | INT | 字数统计 |
| content | LONGTEXT | 法规全文内容 |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

### 2. 法规分块表 `regulation_chunk`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK AUTO_INCREMENT | 分块ID |
| doc_id | BIGINT FK → regulation_document.id | 所属文档ID |
| chunk_index | INT | 分块序号 |
| content | TEXT NOT NULL | 分块内容 |
| char_count | INT | 分块字数 |
| chapter | VARCHAR(200) | 所属章节 |
| create_time | DATETIME | 创建时间 |

### 3. 问答历史表 `qa_history`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK AUTO_INCREMENT | 记录ID |
| question | TEXT NOT NULL | 用户问题 |
| answer | LONGTEXT | 系统回答 |
| sources | TEXT(JSON) | 参考来源(JSON数组) |
| model_name | VARCHAR(100) | 使用的LLM模型 |
| search_strategy | VARCHAR(50) | 检索策略（BM25/向量/混合） |
| top_k | INT | 检索返回数量 |
| create_time | DATETIME | 创建时间 |

### 4. 系统配置表 `system_config`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK AUTO_INCREMENT | 配置ID |
| config_key | VARCHAR(100) UNIQUE | 配置键 |
| config_value | TEXT | 配置值 |
| description | VARCHAR(300) | 配置说明 |
| update_time | DATETIME | 更新时间 |

## 二、Lucene 全文索引（嵌入式）

- 索引目录：`data/lucene/`
- 索引 `regulation_chunk` 表的分块内容
- 使用 `StandardAnalyzer` + `IKAnalyzer`（中文分词）
- 字段索引：
  - `chunkId` (LongPoint, Stored)
  - `docId` (LongPoint, Stored)
  - `title` (TextField)
  - `content` (TextField)
  - `category` (TextField, Stored)
  - `chapter` (TextField, Stored)

## 三、FAISS 向量索引

- 索引文件：`data/faiss/regulation.index`
- Embedding 模型：通过 HTTP API 调用（OpenAI 兼容接口）
- 维度：由 Embedding 模型决定（如 1536 / 768）
- 存储：`chunkId` → 向量 的映射文件 `data/faiss/chunk_mapping.json`

## 四、技术栈

| 层 | 技术 |
|----|------|
| 后端框架 | Spring Boot 2.7.x (Java 8) |
| AI 框架 | Spring AI |
| 关系数据库 | MySQL 5.7+ / MyBatis-Plus |
| 全文搜索 | Apache Lucene 8.x + IKAnalyzer |
| 向量数据库 | FAISS (JNI) |
| Embedding | OpenAI 兼容 API (可配置) |
| LLM | OpenAI 兼容 API (可配置 Key/URL) |
| 前端 | HTML + Vanilla JS |
