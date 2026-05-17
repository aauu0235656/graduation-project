package com.party.regulation.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.party.regulation.config.IndexProperties;
import com.party.regulation.config.LlmProperties;
import com.party.regulation.entity.RegulationChunk;
import com.party.regulation.entity.RegulationDocument;
import com.party.regulation.mapper.RegulationChunkMapper;
import com.party.regulation.mapper.RegulationDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 向量化服务
 * 使用 OpenAI 兼容的 Embedding API 将文本转为向量
 * 本地使用 HashMap 模拟 FAISS 向量索引（可替换为 FAISS JNI）
 *
 */
@Slf4j
@Service
public class EmbeddingService {

    @Autowired
    private LlmProperties llmProperties;  //LLM配置

    @Autowired
    private IndexProperties indexProperties;   //索引配置

    @Autowired
    private RegulationChunkMapper chunkMapper;  //分块数据访问

    @Autowired
    private RegulationDocumentMapper docMapper;  //文档数据访问

    private final OkHttpClient httpClient = new OkHttpClient(); // HTTP客户端

    // 模拟 FAISS 向量索引：chunkId -> 向量
    private final Map<Long, float[]> vectorStore = new ConcurrentHashMap<>(); // 向量存储

    /**
     * 获取向量映射文件路径（从配置读取）
     */
    private String getMappingFile() {
        return indexProperties.getFaissPath() + "/chunk_mapping.json";
    }

    /**
     * 调用 Embedding API 获取文本向量
     */
    public float[] getEmbedding(String text) throws IOException {
        String url = llmProperties.getEmbeddingUrl() + "/embeddings";

        JSONObject body = new JSONObject();
        body.put("model", llmProperties.getEmbeddingModel());//模型名称
        body.put("input", text);

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + llmProperties.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toJSONString(), MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Embedding API 调用失败: " + response.code() + " " + response.body().string());
            }
            String respStr = response.body().string();
            JSONObject resp = JSON.parseObject(respStr);
            JSONArray embedding = resp.getJSONArray("data").getJSONObject(0).getJSONArray("embedding");
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = embedding.getFloatValue(i);
            }
            return vector;
        }
    }

    /**
     * 为文档的所有分块生成向量并存入向量索引
     */
    public void buildVectorIndex(Long docId) throws Exception {
        List<RegulationChunk> chunks = chunkMapper.selectByDocId(docId);
        RegulationDocument doc = docMapper.selectById(docId);
        if (doc == null || chunks.isEmpty()) return;//确保文档和分块存在

        //批量向量化
        for (RegulationChunk chunk : chunks) {
            float[] vector = getEmbedding(chunk.getContent());// 调用API获取向量
            vectorStore.put(chunk.getId(), vector);
        }

        saveMapping();//保存文件
        log.info("文档[{}]的{}个分块已向量化并存入索引", doc.getTitle(), chunks.size());
    }

    /**
     * 向量相似度检索
     * @return 按相似度排序的检索结果
     */
    public List<Map<String, Object>> vectorSearch(String query, int topK) throws Exception {
        float[] queryVector = getEmbedding(query);// 将搜索词转为向量

        // 计算所有向量的余弦相似度
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map.Entry<Long, float[]> entry : vectorStore.entrySet()) {
            Long chunkId = entry.getKey();
            float[] storedVector = entry.getValue();
            float similarity = cosineSimilarity(queryVector, storedVector);// 计算余弦相似度

            if (similarity > llmProperties.getVectorThreshold()) //阈值过滤（过滤掉相关性太低的结果）
            {
                RegulationChunk chunk = chunkMapper.selectById(chunkId);
                RegulationDocument doc = docMapper.selectById(chunk.getDocId());
                if (chunk != null && doc != null) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("chunkId", chunkId);
                    result.put("docId", doc.getId());
                    result.put("title", doc.getTitle());
                    result.put("category", doc.getCategory());
                    result.put("chapter", chunk.getChapter());
                    result.put("content", chunk.getContent());
                    result.put("score", similarity);//相关性分数
                    results.add(result);
                }
            }
        }

        //排序截取（取前K个）
        results.sort((a, b) -> Float.compare((Float) b.get("score"), (Float) a.get("score")));
        return results.subList(0, Math.min(topK, results.size()));
    }

    /**
     * 余弦相似度计算，余弦相似度 = (A·B) / (||A|| * ||B||)
     */
    private float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0f;
        float dotProduct = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return (float) (dotProduct / (Math.sqrt(normA) * Math.sqrt(normB) + 1e-8));
    }

    /**
     * 保存 chunkId 映射到文件（缓存）
     */
    private void saveMapping() throws IOException {
        Path path = Paths.get(getMappingFile());
        Files.createDirectories(path.getParent());// 创建目录
        JSONObject mapping = new JSONObject();
        for (Long chunkId : vectorStore.keySet()) {
            JSONArray vecArr = new JSONArray();
            for (float v : vectorStore.get(chunkId)) vecArr.add(v);
            mapping.put(String.valueOf(chunkId), vecArr);
        }
        Files.write(path, mapping.toJSONString().getBytes("UTF-8"));
        log.info("向量映射已保存到 {}", getMappingFile());
    }

    /**
     * 从文件加载向量映射（从文件加载（恢复））
     */
    public void loadMapping() throws IOException {
        Path path = Paths.get(getMappingFile());
        if (!Files.exists(path)) {
            log.info("向量映射文件不存在，跳过加载");
            return;
        }
        String json = new String(Files.readAllBytes(path), "UTF-8");
        JSONObject mapping = JSON.parseObject(json);
        for (String chunkIdStr : mapping.keySet()) {
            JSONArray vecArr = mapping.getJSONArray(chunkIdStr);
            float[] vector = new float[vecArr.size()];
            for (int i = 0; i < vecArr.size(); i++) vector[i] = vecArr.getFloatValue(i);
            vectorStore.put(Long.parseLong(chunkIdStr), vector);
        }
        log.info("已加载{}条向量映射", vectorStore.size());
    }

    public int getVectorCount() {
        return vectorStore.size();
    }

    public void deleteByDocId(Long docId) {
        // 找到该文档所有分块并删除对应向量
        List<RegulationChunk> chunks = chunkMapper.selectByDocId(docId);
        for (RegulationChunk chunk : chunks) {
            vectorStore.remove(chunk.getId());
        }
    }
}
