package com.party.regulation.controller;

import com.party.regulation.entity.QaHistory;
import com.party.regulation.service.RagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * RAG 问答 Controller
 * RAG 问答核心入口，接收用户问题，调用 RagService 检索+生成
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class RagController {

    @Autowired
    private RagService ragService;

    /**
     * RAG 问答
     * POST /api/chat
     * Body: { "question": "...", "strategy": "hybrid", "topK": 5 }
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();
        try {
            String question = (String) params.get("question");
            String strategy = (String) params.getOrDefault("strategy", "hybrid");
            int topK = params.containsKey("topK") ? ((Number) params.get("topK")).intValue() : 5;
            double temperature = params.containsKey("temperature")
                    ? ((Number) params.get("temperature")).doubleValue() : 0.7;

            if (question == null || question.trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "问题不能为空");
                return result;
            }

            Map<String, Object> ragResult = ragService.chat(question, strategy, topK, temperature);
            result.put("success", true);
            result.putAll(ragResult);//一次性合并所有 key-value
        } catch (Exception e) //捕获 所有异常
        {
            result.put("success", false);
            result.put("message", "问答失败：" + e.getMessage());
            log.error("RAG问答失败", e);
        }
        return result;
    }

    /**
     * 获取问答历史
     */
    @GetMapping("/chat/history")
    public Map<String, Object> getHistory(@RequestParam(required = false, defaultValue = "20") int limit) {
        Map<String, Object> result = new HashMap<>();
        try {
            result.put("success", true);
            result.put("records", ragService.getHistory(limit));
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }
}
