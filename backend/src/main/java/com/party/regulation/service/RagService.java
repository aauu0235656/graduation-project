package com.party.regulation.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.party.regulation.config.LlmProperties;
import com.party.regulation.entity.QaHistory;
import com.party.regulation.mapper.QaHistoryMapper;
import com.party.regulation.mapper.RegulationChunkMapper;
import com.party.regulation.mapper.RegulationDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * RAG 问答服务核心
 * 检索策略：BM25（Lucene）/ 向量检索 / 混合检索
 * 生成策略：检索增强生成（RAG）
 * RAG 核心：双路检索（Lucene+向量）→ RRF 融合 → 构建 Context → 调用 LLM 生成回答
 */
@Slf4j
@Service
public class RagService {

    @Autowired
    private LuceneService luceneService;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private LlmProperties llmProperties;

    @Autowired
    private QaHistoryMapper qaHistoryMapper;

    @Autowired
    private RegulationChunkMapper chunkMapper;

    @Autowired
    private RegulationDocumentMapper docMapper;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    /**
     * RAG 问答主入口
     * @param question 用户问题
     * @param strategy 检索策略：bm25 / vector / hybrid
     * @param topK 检索返回数量
     * @param temperature 大模型生成温度
     * @return 问答结果
     */
    public Map<String, Object> chat(String question, String strategy, int topK, double temperature) throws Exception {
        /*
        用户提问
   ↓
retrieve（检索）
   ↓
buildContext（构建上下文）
   ↓
callLlm（生成答案）
   ↓
extractSources（提取来源）
   ↓
保存数据库
   ↓
返回 JSON
        */
        long startTime = System.currentTimeMillis();

        // 1. 检索阶段
        List<Map<String, Object>> retrievedDocs = retrieve(question, strategy, topK);

        // 2. 构建增强上下文
        String context = buildContext(retrievedDocs);

        // 3. 调用 LLM 生成回答
        String answer = callLlm(question, context, temperature);

        // 4. 提取参考来源
        List<Map<String, String>> sources = extractSources(retrievedDocs);

        long costTime = System.currentTimeMillis() - startTime;

        // 5. 保存问答历史
        QaHistory history = new QaHistory();
        history.setQuestion(question);
        history.setAnswer(answer);
        history.setSources(JSON.toJSONString(sources));
        history.setModelName(llmProperties.getModel());
        history.setSearchStrategy(strategy);
        history.setTopK(topK);
        qaHistoryMapper.insert(history);

        // 6. 返回结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("sources", sources);
        result.put("retrievedDocs", retrievedDocs);
        result.put("strategy", strategy);
        result.put("model", llmProperties.getModel());
        result.put("costTime", costTime);

        log.info("RAG问答完成，耗时{}ms，检索{}条文档", costTime, retrievedDocs.size());
        return result;
    }

    /**
     * 检索阶段：根据策略选择检索方式
     */
    private List<Map<String, Object>> retrieve(String question, String strategy, int topK) throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();

        switch (strategy.toLowerCase()) {
            case "bm25"://精确关键词匹配
                results = luceneService.search(question, null, topK);
                break;
            case "vector"://语义匹配
                results = embeddingService.vectorSearch(question, topK);
                break;
            case "hybrid":
            default:
                // 混合检索：BM25 + 向量，结果去重合并
                List<Map<String, Object>> bm25Results = luceneService.search(question, null, topK);
                List<Map<String, Object>> vectorResults = embeddingService.vectorSearch(question, topK);
                results = mergeResults(bm25Results, vectorResults, topK);
                break;
        }

        // 过滤掉内容为空的文档块，并补充被跳过的条数
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> item : results) {
            String content = (String) item.getOrDefault("content", "");
            if (content != null && !content.trim().isEmpty()) //只保留有正文的内容
            {
                filtered.add(item);
            }
            if (filtered.size() >= topK) break;
        }

        log.info("检索阶段：原始{}条，过滤空内容后{}条", results.size(), filtered.size());
        return filtered;
    }

    /**
     * 合并 BM25 和向量检索结果（去重 + RRF 融合排序），只看排名
     */
    private List<Map<String, Object>> mergeResults(List<Map<String, Object>> bm25, List<Map<String, Object>> vector, int topK) {
        Map<String, Double> rrfScores = new LinkedHashMap<>();

        // BM25 结果打分 (权重0.5)
        for (int i = 0; i < bm25.size(); i++) {
            String key = String.valueOf(bm25.get(i).get("chunkId"));
            double score = 1.0 / (60 + i + 1);
            rrfScores.merge(key, score * 0.5, Double::sum);
        }

        // 向量结果打分 (权重0.5)
        for (int i = 0; i < vector.size(); i++) {
            String key = String.valueOf(vector.get(i).get("chunkId"));
            double score = 1.0 / (60 + i + 1);
            rrfScores.merge(key, score * 0.5, Double::sum);
        }

        // 合并去重，同一分块不重复送给LLM
        Map<Long, Map<String, Object>> mergedMap = new LinkedHashMap<>();
        // 先放BM25结果
        for (Map<String, Object> item : bm25) {
            Long chunkId = Long.parseLong(String.valueOf(item.get("chunkId")));
            item.put("rrfScore", rrfScores.getOrDefault(String.valueOf(chunkId), 0.0));
            mergedMap.put(chunkId, item);
        }
        // 补充向量结果中BM25没有的
        for (Map<String, Object> item : vector) {
            Long chunkId = Long.parseLong(String.valueOf(item.get("chunkId")));
            if (!mergedMap.containsKey(chunkId)) {
                item.put("rrfScore", rrfScores.getOrDefault(String.valueOf(chunkId), 0.0));
                mergedMap.put(chunkId, item);
            } else {
                mergedMap.get(chunkId).put("vectorScore", item.get("score"));//只补充向量分数
            }
        }

        // 按 RRF 分数排序（降序）
        List<Map<String, Object>> merged = new ArrayList<>(mergedMap.values());
        merged.sort((a, b) -> Double.compare(
                (Double) b.getOrDefault("rrfScore", 0.0),
                (Double) a.getOrDefault("rrfScore", 0.0)));

        return merged.subList(0, Math.min(topK, merged.size()));
    }

    /**
     * 构建增强上下文 Prompt
     * 同一文档（title+chapter）的多个分块合并为同一个参考资料编号
     */
    private String buildContext(List<Map<String, Object>> docs) {
        if (docs.isEmpty()) return "";//避免喂垃圾

        StringBuilder sb = new StringBuilder();
        sb.append("以下是从党内法规知识库中检索到的相关内容：\n\n");

        // 按文档标题+章节去重分组，同一文档只占一个参考资料编号
        Map<String, List<Map<String, Object>>> docGroups = new LinkedHashMap<>();
        for (Map<String, Object> doc : docs) {
            String content = (String) doc.getOrDefault("content", "");
            if (content == null || content.trim().isEmpty()) continue;
            String key = (String) doc.getOrDefault("title", "") + "|||" + (String) doc.getOrDefault("chapter", "");
            docGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(doc);
        }

        int refIndex = 0;
        for (List<Map<String, Object>> group : docGroups.values()) {
            refIndex++;
            // 取该组第一个分块的信息作为头部
            Map<String, Object> first = group.get(0);
            sb.append("【参考资料").append(refIndex).append("】");
            sb.append(first.getOrDefault("title", "未知法规"));
            String chapter = (String) first.getOrDefault("chapter", "");
            if (chapter != null && !chapter.isEmpty()) {
                sb.append(" · ").append(chapter);
            }
            sb.append("\n");

            // 将该文档所有分块的内容拼接起来（每块限500字）
            for (int i = 0; i < group.size(); i++) {
                String content = (String) group.get(i).getOrDefault("content", "");
                String truncated = content.length() > 500 ? content.substring(0, 500) + "..." : content;
                sb.append(truncated);
                if (i < group.size() - 1) sb.append("\n");
            }
            sb.append("\n\n");
        }

        if (refIndex == 0) return "";
        return sb.toString();
    }

    /**
     * 调用 LLM API 生成回答
     */
    private String callLlm(String question, String context, double temperature) throws IOException {
        String url = llmProperties.getApiUrl() + "/chat/completions";//LLM API地址

        String systemPrompt = "你是一位精通中国共产党党内法规的专家助手。你的任务是基于提供的参考资料，准确回答用户关于党内法规的问题。\n"
                + "要求：\n"
                + "1. 严格基于提供的参考资料回答，不要编造内容\n"
                + "2. 如果参考资料不足以回答问题，请诚实说明\n"
                + "3. 回答应条理清晰，引用具体法规条文\n"
                + "4. 使用正式、专业的语言风格\n"
                + "5. 回答中引用的【参考资料X】编号必须与上文提供的参考资料完全一致，严禁编造不存在的引用编号";

        StringBuilder userPrompt = new StringBuilder();//构建用户prompt
        if (context != null && !context.isEmpty()) {
            userPrompt.append(context).append("\n");
        }
        userPrompt.append("用户问题：").append(question);

        JSONObject body = new JSONObject();
        body.put("model", llmProperties.getModel());
        body.put("temperature", temperature);
        body.put("max_tokens", llmProperties.getMaxTokens());

        JSONArray messages = new JSONArray();//模型的回答
        messages.add(new JSONObject() {{
            put("role", "system");
            put("content", systemPrompt);
        }});
        messages.add(new JSONObject() {{
            put("role", "user");
            put("content", userPrompt.toString());
        }});
        body.put("messages", messages);

        Request request = new Request.Builder()//发送HTTP请求
                .url(url)
                .addHeader("Authorization", "Bearer " + llmProperties.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toJSONString(), MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null ? response.body().string() : "";
                log.error("LLM API 调用失败: {} {}", response.code(), errBody);
                throw new IOException("LLM API 调用失败: " + response.code());
            }
            String respStr = response.body().string();//提取模型生成文字， 返回给 Controller → 前端
            JSONObject resp = JSON.parseObject(respStr);
            return resp.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
        }
    }

    /**
     * 提取参考来源列表（与 buildContext 编号严格对齐）
     * 同一文档（title+chapter）只保留一条，使用 HashSet 全局去重（不依赖相邻性）
     */
    private List<Map<String, String>> extractSources(List<Map<String, Object>> docs) {
        List<Map<String, String>> sources = new ArrayList<>();
        int refIndex = 0;
        Set<String> seenKeys = new HashSet<>();
        for (Map<String, Object> doc : docs) {
            String content = (String) doc.getOrDefault("content", "");
            if (content == null || content.trim().isEmpty()) continue;

            String title = (String) doc.getOrDefault("title", "");
            String chapter = (String) doc.getOrDefault("chapter", "");
            String key = title + "|||" + chapter;
            if (seenKeys.contains(key)) continue; // 同一文档只出现一次（全局去重）
            seenKeys.add(key);

            refIndex++;
            Map<String, String> source = new LinkedHashMap<>();
            source.put("refIndex", String.valueOf(refIndex));
            source.put("docId", String.valueOf(doc.getOrDefault("docId", "")));
            source.put("title", title.isEmpty() ? "未知法规" : title);
            source.put("chapter", chapter);
            source.put("score", String.valueOf(doc.getOrDefault("score", 0)));
            sources.add(source);
        }
        return sources;
    }

    /**
     * 获取问答历史
     */
    public List<QaHistory> getHistory(int limit) {//按创建时间倒序排序放回有限条，相当于sql语句
        return qaHistoryMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QaHistory>()
                        .orderByDesc(QaHistory::getCreateTime)
                        .last("LIMIT " + limit));
    }
}
