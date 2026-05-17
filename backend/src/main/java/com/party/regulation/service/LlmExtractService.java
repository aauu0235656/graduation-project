package com.party.regulation.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.party.regulation.config.LlmProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 大模型智能提取服务
 * 调用阿里云百炼 API 对爬取的文章内容进行分析：
 * - 生成文章摘要
 * - 提炼关键知识点
 * - 生成问答对
 * 调用 qwen-plus 做文章分析（摘要、关键词、知识点、问答对
 */
@Slf4j
@Service
public class LlmExtractService {

    @Autowired
    private LlmProperties llmProperties;//LLM配置

    @Value("${llm.extract-temperature:0.3}")
    private Double extractTemperature;//温度参数

    @Value("${llm.extract-max-length:3000}")
    private Integer extractMaxLength;//最大文本长度

    private String systemPromptTemplate;//系统提示词模板

    private String analyzePromptTemplate;//文章分析提示词模板

    private String generateQaPromptTemplate;//问答生成提示词模板

    /**
     * 初始化时加载 Prompt 模板文件
     */
    @javax.annotation.PostConstruct
    public void init() {
        try {
            //加载Prompt模板
            systemPromptTemplate = loadTemplate("prompts/system-prompt.txt");
            analyzePromptTemplate = loadTemplate("prompts/analyze-article.txt");
            generateQaPromptTemplate = loadTemplate("prompts/generate-qa.txt");
            log.info("已加载3个 Prompt 模板文件");
        } catch (IOException e) {
            log.error("加载 Prompt 模板文件失败", e);
            throw new RuntimeException("加载 Prompt 模板文件失败", e);
        }
    }

    //加载模板的方法
    private String loadTemplate(String classpath) throws IOException {
        ClassPathResource resource = new ClassPathResource(classpath);
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }

    /**
     * 替换模板中的占位符
     * 模板："标题：${title}\n内容：${content}"
     * 替换后："标题：纪律处分条例\n内容：第一条 为了维护党的纪律..."
     */
    private String renderTemplate(String template, Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    /**
     * 对单篇文章进行 AI 分析（类别识别 + 摘要 + 关键词 + 知识点）
     *
     * @param title   文章标题
     * @param content 文章正文
     * @return Map 包含 category（分类）、categoryReason（分类理由）、summary（摘要）和 keyPoints（关键知识点列表）
     * {
     *     "category": "党内法规",
     *     "categoryReason": "本文详细解读了党的纪律处分条例，属于党内法规范畴",
     *     "summary": "本文主要介绍了纪律处分条例的主要内容...",
     *     "keyPoints": [
     *         "纪律处分的种类包括警告、严重警告等",
     *         "从重处分的情形有五种"
     *     ],
     *     "keywords": ["纪律处分", "党内法规", "党风廉政建设"]
     * }
     */
    public Map<String, Object> analyzeArticle(String title, String content) throws IOException {
        log.info("大模型分析文章: {}", title);

        // 截取正文，避免超出 token 限制
        String truncatedContent = content.length() > extractMaxLength
                ? content.substring(0, extractMaxLength) + "..."
                : content;

        //构建prompt
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("title", title);
        vars.put("content", truncatedContent);
        String prompt = renderTemplate(analyzePromptTemplate, vars);

        String result = callLlm(prompt);//调用大模型

        // 解析返回的 JSON
        try {
            JSONObject json = JSON.parseObject(result);
            Map<String, Object> analysis = new LinkedHashMap<>();

            //提取分类
            analysis.put("category", json.getString("category"));
            analysis.put("categoryReason", json.getString("categoryReason"));
            analysis.put("summary", json.getString("summary"));

            //提取关键知识点
            List<String> keyPoints = new ArrayList<>();
            if (json.containsKey("keyPoints")) {
                for (Object obj : json.getJSONArray("keyPoints")) {
                    keyPoints.add(obj.toString());
                }
            }
            analysis.put("keyPoints", keyPoints);

            //提取关键词
            List<String> keywords = new ArrayList<>();
            if (json.containsKey("keywords")) {
                for (Object obj : json.getJSONArray("keywords")) {
                    keywords.add(obj.toString());
                }
            }
            analysis.put("keywords", keywords);

            return analysis;
        } catch (Exception e) {
            //解析失败时的降级策略
            log.warn("解析大模型返回结果失败，使用原文作为摘要: {}", e.getMessage());
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("category", "其他");
            fallback.put("categoryReason", "解析失败，默认归类");
            fallback.put("summary", content.length() > 200 ? content.substring(0, 200) + "..." : content);
            fallback.put("keyPoints", Collections.emptyList());
            fallback.put("keywords", Collections.emptyList());
            return fallback;
        }
    }

    /**
     * 为文章生成问答对（用于构建知识库）
     *
     * @param title   文章标题
     * @param content 文章正文
     * @return 问答对列表，每项含 question 和 answer
     *  "question": "纪律处分有哪些种类？",
     *  "answer": "警告、严重警告、撤销党内职务、留党察看、开除党籍。"
     */
    public List<Map<String, String>> generateQaPairs(String title, String content) throws IOException {
        log.info("生成问答对: {}", title);

        //文本截断
        String truncatedContent = content.length() > extractMaxLength
                ? content.substring(0, extractMaxLength) + "..."
                : content;

        //构建prompt
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("title", title);
        vars.put("content", truncatedContent);
        String prompt = renderTemplate(generateQaPromptTemplate, vars);

        String result = callLlm(prompt);

        try {
            JSONArray arr = JSON.parseArray(result);//arr是一个数组
            List<Map<String, String>> qaPairs = new ArrayList<>();//回答
            for (int i = 0; i < arr.size(); i++) {
                JSONObject item = arr.getJSONObject(i);
                Map<String, String> pair = new LinkedHashMap<>();
                pair.put("question", item.getString("question"));
                pair.put("answer", item.getString("answer"));
                qaPairs.add(pair);//装入最终模板
            }
            return qaPairs;
        } catch (Exception e) {
            log.warn("解析问答对失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 调用大模型 API
     */
    private String callLlm(String prompt) throws IOException {
        String url = llmProperties.getApiUrl() + "/chat/completions";///chat/completions 是 OpenAI 兼容接口的标准路径

        JSONObject body = new JSONObject();
        body.put("model", llmProperties.getModel());//qwen-plus
        body.put("temperature", extractTemperature);
        body.put("max_tokens", llmProperties.getMaxTokens());

        JSONArray messages = new JSONArray();//对话结构
        messages.add(new JSONObject() {{//设定系统角色（设定AI的身份和行为规则）
            put("role", "system");
            put("content", systemPromptTemplate);
        }});
        messages.add(new JSONObject() {{//设定用户角色（用户实际要问的/让模型做的事）
            put("role", "user");
            put("content", prompt);
        }});
        body.put("messages", messages);

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + llmProperties.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toJSONString(),
                        MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null ? response.body().string() : "";
                log.error("大模型 API 调用失败: {} {}", response.code(), errBody);
                throw new IOException("大模型 API 调用失败: " + response.code());
            }
            String respStr = response.body().string();// 原始JSON字符串
            JSONObject resp = JSON.parseObject(respStr);// 解析成JSON对象
            return resp.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
        }
    }
}
