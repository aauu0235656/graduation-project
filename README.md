# 党内法规知识库问答系统

基于大语言模型（LLM）与检索增强生成（RAG）技术的党内法规知识库问答系统。

## 技术架构

```
┌─────────────┐    HTTP API    ┌──────────────────────────────────────┐
│             │ ─────────────> │          Spring Boot 后端              │
│   前端 UI   │ <───────────── │  ┌─────────┐  ┌──────┐  ┌─────────┐ │
│  (HTML/JS)  │                │  │Controller│→│Service│→│  Store  │ │
│             │                │  └─────────┘  └──────┘  └─────────┘ │
└─────────────┘                │     │           │          │       │
                               │     ▼           ▼          ▼       │
                               │  ┌──────┐  ┌───────┐  ┌──────┐   │
                               │  │MySQL │  │Lucene │  │FAISS │   │
                               │  └──────┘  └───────┘  └──────┘   │
                               └──────────────────────────────────────┘
                                                      │
                                                      ▼
                                            ┌──────────────────┐
                                            │  LLM / Embedding │
                                            │  API (OpenAI兼容) │
                                            └──────────────────┘
```

## 快速开始

### 1. 环境要求
- Java 8+
- Maven 3.6+
- MySQL 5.7+
- Node.js 16+（前端预览）

### 2. 数据库初始化
```sql
# 创建数据库并执行初始化脚本
mysql -u root -p < backend/init.sql
```

### 3. 配置后端
编辑 `backend/src/main/resources/application.yml`：
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/party_regulation_db?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai
    username: root        # 改为你的MySQL用户名
    password: root        # 改为你的MySQL密码

llm:
  api-key: sk-xxx         # 改为你的 LLM API Key
  api-url: https://api.openai.com/v1   # 改为你的 API 地址
  model: gpt-3.5-turbo    # 改为你的模型名称
  embedding-url: https://api.openai.com/v1
  embedding-model: text-embedding-ada-002
```

### 4. 启动后端
```bash
cd backend
mvn spring-boot:run
# 或在 IDEA 中直接运行 RegulationQaApplication.java
```

### 5. 启动前端
```bash
# 在项目根目录
npx serve . --listen 3000
# 访问 http://localhost:3000
```

## 项目结构

```
d:\bishe\
├── index.html                    # 前端页面
├── backend/
│   ├── pom.xml                   # Maven 依赖配置
│   ├── init.sql                  # 数据库初始化脚本
│   ├── database-design.md        # 数据库设计文档
│   └── src/main/java/com/party/regulation/
│       ├── RegulationQaApplication.java    # 启动类
│       ├── config/
│       │   ├── CorsConfig.java            # 跨域配置
│       │   ├── IndexProperties.java       # 索引参数配置
│       │   └── LlmProperties.java         # LLM 配置
│       ├── controller/
│       │   ├── IndexController.java       # 索引构建 API
│       │   ├── SearchController.java      # 全文检索 API
│       │   ├── RagController.java         # RAG 问答 API
│       │   └── DatasetController.java     # 数据集管理 API
│       ├── entity/
│       │   ├── RegulationDocument.java    # 法规文档实体
│       │   ├── RegulationChunk.java       # 法规分块实体
│       │   ├── QaHistory.java             # 问答历史实体
│       │   └── SystemConfig.java          # 系统配置实体
│       ├── mapper/
│       │   ├── RegulationDocumentMapper.java
│       │   ├── RegulationChunkMapper.java
│       │   ├── QaHistoryMapper.java
│       │   └── SystemConfigMapper.java
│       └── service/
│           ├── LuceneService.java         # Lucene 全文索引服务
│           ├── EmbeddingService.java      # 向量化服务 (FAISS)
│           ├── ChunkService.java          # 文本分块服务
│           └── RagService.java            # RAG 问答核心服务
```

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/document/add | 手动录入法规 |
| POST | /api/document/upload | 文件上传导入 |
| POST | /api/document/index/{id} | 单文档索引构建 |
| POST | /api/document/index/batch | 批量索引构建 |
| GET  | /api/documents | 获取文档列表 |
| GET  | /api/document/{id} | 获取文档详情 |
| DELETE | /api/document/{id} | 删除文档 |
| GET  | /api/search | 关键词全文检索 |
| POST | /api/chat | RAG 智能问答 |
| GET  | /api/chat/history | 问答历史 |
| GET  | /api/stats | 系统统计 |
| GET  | /api/config | 获取配置 |
| POST | /api/config | 更新配置 |

## 核心功能

1. **全文索引构建**：Lucene + IK中文分词，对法规文档分块、建立倒排索引
2. **关键词全文检索**：BM25评分，多字段搜索，关键词高亮
3. **RAG智能问答**：混合检索（BM25+向量）+ LLM生成，支持三种检索策略
