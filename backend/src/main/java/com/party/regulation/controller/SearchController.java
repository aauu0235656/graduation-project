package com.party.regulation.controller;

import com.party.regulation.service.LuceneService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 全文检索 Controller，，调用 LuceneService 做关键词搜索
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class SearchController {

    @Autowired
    private LuceneService luceneService;//依赖注入

    /**
     * 关键词全文检索（BM25）
     * GET /api/search?keyword=纪律处分&category=纪律法规&topK=10
     */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String keyword,
                                       @RequestParam(required = false, defaultValue = "all") String category,
                                       @RequestParam(required = false, defaultValue = "10") int topK) {
        Map<String, Object> result = new HashMap<>();
        try {
            /*调用LuceneService的搜索方法，返回原始文档列表，每个文档包含：docId、title、category、score、highlightContent 等
            */
            List<Map<String, Object>> docs = luceneService.search(keyword, category, topK);

            // 按 docId 去重合并，以 docId为键，合并相同文档的多个片段
            Map<String, Map<String, Object>> mergedDocs = new LinkedHashMap<>();
            for (Map<String, Object> doc : docs) {
                String docId = String.valueOf(doc.get("docId"));
                //构建合并文档结构
                if (!mergedDocs.containsKey(docId)) {
                    Map<String, Object> merged = new LinkedHashMap<>();
                    merged.put("docId", docId);
                    merged.put("title", doc.get("title"));
                    merged.put("category", doc.get("category"));
                    merged.put("snippets", new ArrayList<String>());//存储高亮片段，前端展示时，匹配的关键词会显示为红色加粗，让用户一眼看到为什么这篇文档被检索出来。
                    merged.put("maxScore", doc.get("score"));//文档的最高得分
                    mergedDocs.put(docId, merged);
                }
                //处理高亮片段
                String snippet = (String) doc.getOrDefault("highlightContent", "");
                if (!snippet.isEmpty()) {
                    ((List<String>) mergedDocs.get(docId).get("snippets")).add(snippet);
                }
                //更新·更高分数
                float currentMax = (Float) mergedDocs.get(docId).get("maxScore");
                if ((Float) doc.get("score") > currentMax) {
                    mergedDocs.get(docId).put("maxScore", doc.get("score"));
                }
            }

            result.put("success", true);
            result.put("keyword", keyword);
            result.put("total", mergedDocs.size());
            result.put("results", new ArrayList<>(mergedDocs.values()));
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "检索失败：" + e.getMessage());
            log.error("检索失败", e);
        }
        return result;
    }
    /*
    "success": true,
  "keyword": "纪律处分",
  "total": 5,
  "results": [
    {
      "docId": "123",
      "title": "中国共产党纪律处分条例",
      "category": "纪律法规",
      "snippets": [
        "违反<em>纪律处分</em>条例的...",
        "对于<em>纪律处分</em>的决定..."
      ],
      "maxScore": 8.76
    }
  ]
     */
    /**
     * 获取索引统计信息，要是当前索引里一共有多少条记录。
     */
    @GetMapping("/index/stats")
    public Map<String, Object> getIndexStats() {
        Map<String, Object> result = new HashMap<>();
        try {
            result.put("success", true);
            result.put("luceneDocCount", luceneService.getDocCount());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }
}
