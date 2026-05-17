package com.party.regulation.controller;

import com.party.regulation.entity.RegulationDocument;
import com.party.regulation.mapper.RegulationDocumentMapper;
import com.party.regulation.service.CrawlerService;
import com.party.regulation.service.LlmExtractService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 爬虫控制器 - 学习强国专用，爬虫采集入口，从学习强国抓取文章
 * 内容预览		CrawlerService.crawlXuexiByUrl()
 * 单篇入库	CrawlerService → 入库
 * 批量入库	CrawlerService → 循环入库
 * AI分析	CrawlerService → LlmExtractService.analyzeArticle()
 * 批量分析	CrawlerService → 循环调用 analyzeArticle()
 * 生成问答对	CrawlerService → LlmExtractService.generateQaPairs()
 */
@Slf4j
@RestController
@RequestMapping("/api/crawler")
public class CrawlerController {

    @Autowired
    private CrawlerService crawlerService;  // 爬虫核心服务：负责下载网页、解析HTML

    @Autowired
    private RegulationDocumentMapper docMapper; // 文档Mapper：负责将爬取的数据存入MySQL

    @Autowired
    private LlmExtractService llmExtractService;// AI服务：负责从文章内容中提取结构化信息或生成问答对

    // ==================== 学习强国专用接口 ====================

    /**
     * 学习强国 - 预览文章（不存库），直接把 Map返回给前端展示。
     * POST /api/crawler/xuexi/preview
     * Body: { "articleId": "13226399" } 或 { "url": "https://www.xuexi.cn/lgpage/detail/index.html?id=13226399" }
     */
    @PostMapping("/xuexi/preview")
    public Map<String, Object> xuexiPreview(@RequestBody Map<String, String> params) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, String> article;
            //判断参数是 articleId 还是 url
            if (params.containsKey("articleId") && !params.get("articleId").isEmpty()) {
                article = crawlerService.crawlXuexiByArticleId(params.get("articleId").trim());
            } else if (params.containsKey("url") && !params.get("url").isEmpty()) {
                article = crawlerService.crawlXuexiByUrl(params.get("url").trim());
            } else {
                result.put("success", false);
                result.put("message", "请提供 articleId 或 url");
                return result;
            }
            result.put("success", true);
            result.put("data", article);
        } catch (Exception e) {
            log.error("学习强国爬取失败", e);
            result.put("success", false);
            result.put("message", "爬取失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 学习强国 - 单篇爬取并存入数据库，状态设为 0（待索引）（把 Map中的数据映射到 RegulationDocument实体类，调用 docMapper.insert()存入 MySQL。）
     * POST /api/crawler/xuexi/save
     * Body: { "url": "...", "category": "其他" }
     */
    @PostMapping("/xuexi/save")
    public Map<String, Object> xuexiSave(@RequestBody Map<String, String> params) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, String> article;
            if (params.containsKey("articleId") && !params.get("articleId").isEmpty()) {
                article = crawlerService.crawlXuexiByArticleId(params.get("articleId").trim());
            } else if (params.containsKey("url") && !params.get("url").isEmpty()) {
                article = crawlerService.crawlXuexiByUrl(params.get("url").trim());
            } else {
                result.put("success", false);
                result.put("message", "请提供 articleId 或 url");
                return result;
            }

            //数据校验
            String content = article.get("content");
            if (content == null || content.trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "文章正文为空，无法入库");
                return result;
            }

            // 组装实体并入库
            RegulationDocument doc = new RegulationDocument();
            doc.setTitle(article.get("title"));
            doc.setCategory(params.getOrDefault("category", "其他"));// 默认分类为“其他”
            doc.setOrg(article.getOrDefault("source", ""));// 来源
            doc.setContent(content); // 正文
            doc.setHtmlContent(article.getOrDefault("htmlContent", "")); // 字数统计
            doc.setCharCount(content.length());
            doc.setStatus(0); // 待索引
            doc.setFilePath(article.getOrDefault("url", ""));
            docMapper.insert(doc);   // 执行插入

            result.put("success", true);
            result.put("message", "入库成功");
            result.put("docId", doc.getId());
            result.put("title", doc.getTitle());
        } catch (Exception e) {
            log.error("学习强国文章入库失败", e);
            result.put("success", false);
            result.put("message", "入库失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 学习强国 - 批量爬取并存入数据库
     * POST /api/crawler/xuexi/save-multi
     * Body: { "urls": ["url1", "url2"], "category": "其他" }
     */
    @PostMapping("/xuexi/save-multi")
    public Map<String, Object> xuexiSaveMulti(@RequestBody Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            List<String> urls = (List<String>) params.get("urls");
            String category = params.getOrDefault("category", "其他").toString();

            if (urls == null || urls.isEmpty()) {
                result.put("success", false);
                result.put("message", "请提供 urls");
                return result;
            }

            int successCount = 0;
            int failCount = 0;
            List<String> titles = new ArrayList<>();
      // 循环调用爬取逻辑
            for (String url : urls) {
                if (url == null || url.trim().isEmpty()) continue;
                try {
                    Map<String, String> article = crawlerService.crawlXuexiByUrl(url.trim());
                    String content = article.get("content");
                    if (content != null && content.length() > 100) {
                        RegulationDocument doc = new RegulationDocument();
                        doc.setTitle(article.get("title"));
                        doc.setCategory(category);
                        doc.setOrg(article.getOrDefault("source", ""));
                        doc.setContent(content);
                        doc.setHtmlContent(article.getOrDefault("htmlContent", ""));
                        doc.setCharCount(content.length());
                        doc.setStatus(0);
                        doc.setFilePath(article.getOrDefault("url", ""));
                        docMapper.insert(doc);
                        successCount++;
                        titles.add(doc.getTitle());
                    } else {
                        failCount++;
                    }
                    Thread.sleep(800);
                } catch (Exception e) {
                    log.warn("跳过入库失败的文章: {} - {}", url, e.getMessage());
                    failCount++;
                }
            }

            result.put("success", true);
            result.put("total", urls.size());
            result.put("successCount", successCount);
            result.put("failCount", failCount);
            result.put("titles", titles);
            result.put("message", "批量入库完成：" + successCount + " 篇成功" + (failCount > 0 ? "，" + failCount + " 篇失败" : ""));
        } catch (Exception e) {
            log.error("学习强国批量入库失败", e);
            result.put("success", false);
            result.put("message", "批量入库失败：" + e.getMessage());
        }
        return result;
    }

    // ==================== AI 分析接口 ====================

    /**
     * 学习强国 - 单篇 AI 分析（摘要 + 关键词 + 知识点）
     * POST /api/crawler/xuexi/analyze
     * Body: { "url": "..." } 或 { "articleId": "..." }
     */
    @PostMapping("/xuexi/analyze")
    public Map<String, Object> xuexiAnalyze(@RequestBody Map<String, String> params) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, String> article;
            if (params.containsKey("articleId") && !params.get("articleId").isEmpty()) {
                article = crawlerService.crawlXuexiByArticleId(params.get("articleId").trim());
            } else if (params.containsKey("url") && !params.get("url").isEmpty()) {
                article = crawlerService.crawlXuexiByUrl(params.get("url").trim());
            } else {
                result.put("success", false);
                result.put("message", "请提供 articleId 或 url");
                return result;
            }

            String title = article.get("title");
            String content = article.get("content");
            if (content == null || content.trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "文章正文为空，无法分析");
                return result;
            }

            // 调用大模型分析
            Map<String, Object> analysis = llmExtractService.analyzeArticle(title, content);

            result.put("success", true);
            result.put("title", title);
            result.put("source", article.getOrDefault("source", ""));
            result.put("publishDate", article.getOrDefault("publishDate", ""));
            result.put("url", article.getOrDefault("url", ""));
            result.put("analysis", analysis);
        } catch (Exception e) {
            log.error("AI 分析失败", e);
            result.put("success", false);
            result.put("message", "AI 分析失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 学习强国 - 批量 AI 分析（摘要 + 关键词 + 知识点）
     * POST /api/crawler/xuexi/analyze-multi
     * Body: { "urls": ["url1", "url2"] }
     */
    @PostMapping("/xuexi/analyze-multi")
    public Map<String, Object> xuexiAnalyzeMulti(@RequestBody Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            List<String> urls = (List<String>) params.get("urls");

            if (urls == null || urls.isEmpty()) {
                result.put("success", false);
                result.put("message", "请提供 urls");
                return result;
            }

            List<Map<String, Object>> items = new ArrayList<>();
            int successCount = 0;
            int failCount = 0;

            for (String url : urls) {
                if (url == null || url.trim().isEmpty()) continue;
                try {
                    Map<String, String> article = crawlerService.crawlXuexiByUrl(url.trim());
                    String content = article.get("content");

                    if (content != null && content.length() > 100) {
                        Map<String, Object> analysis = llmExtractService.analyzeArticle(
                                article.get("title"), content);

                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("title", article.get("title"));
                        item.put("source", article.getOrDefault("source", ""));
                        item.put("publishDate", article.getOrDefault("publishDate", ""));
                        item.put("url", article.getOrDefault("url", ""));
                        item.put("analysis", analysis);
                        items.add(item);
                        successCount++;
                    } else {
                        failCount++;
                    }
                    Thread.sleep(800);
                } catch (Exception e) {
                    log.warn("跳过分析失败的文章: {} - {}", url, e.getMessage());
                    failCount++;
                }
            }

            result.put("success", true);
            result.put("total", urls.size());
            result.put("successCount", successCount);
            result.put("failCount", failCount);
            result.put("items", items);
            result.put("message", "批量分析完成：" + successCount + " 篇成功" + (failCount > 0 ? "，" + failCount + " 篇失败" : ""));
        } catch (Exception e) {
            log.error("批量 AI 分析失败", e);
            result.put("success", false);
            result.put("message", "批量 AI 分析失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 学习强国 - 生成问答对
     * POST /api/crawler/xuexi/generate-qa
     * Body: { "url": "..." } 或 { "articleId": "..." }
     */
    @PostMapping("/xuexi/generate-qa")
    public Map<String, Object> xuexiGenerateQa(@RequestBody Map<String, String> params) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, String> article;
            if (params.containsKey("articleId") && !params.get("articleId").isEmpty()) {
                article = crawlerService.crawlXuexiByArticleId(params.get("articleId").trim());
            } else if (params.containsKey("url") && !params.get("url").isEmpty()) {
                article = crawlerService.crawlXuexiByUrl(params.get("url").trim());
            } else {
                result.put("success", false);
                result.put("message", "请提供 articleId 或 url");
                return result;
            }

            String title = article.get("title");
            String content = article.get("content");
            if (content == null || content.trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "文章正文为空，无法生成问答对");
                return result;
            }

            // 调用大模型生成问答对
            List<Map<String, String>> qaPairs = llmExtractService.generateQaPairs(title, content);

            result.put("success", true);
            result.put("title", title);
            result.put("source", article.getOrDefault("source", ""));
            result.put("publishDate", article.getOrDefault("publishDate", ""));
            result.put("url", article.getOrDefault("url", ""));
            result.put("qaPairs", qaPairs);
        } catch (Exception e) {
            log.error("生成问答对失败", e);
            result.put("success", false);
            result.put("message", "生成问答对失败：" + e.getMessage());
        }
        return result;
    }
}
