package com.party.regulation.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.party.regulation.entity.RegulationDocument;
import com.party.regulation.mapper.RegulationChunkMapper;
import com.party.regulation.mapper.RegulationDocumentMapper;
import com.party.regulation.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.LocalDate;
import java.util.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

/**
 * 数据导入与索引构建 Controller，索引管理，触发构建/重建 Lucene 索引和 FAISS 向量索引
 *POST /document/add — 手动录入文档
 *POST /document/upload — 文件上传（PDF/DOCX/TXT）
 *POST /document/index/{docId} — 单文档建索引（最重要）
 * POST /document/index/batch — 批量建索引
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class IndexController {

    @Autowired
    private RegulationDocumentMapper docMapper; // 文档数据访问

    @Autowired
    private RegulationChunkMapper chunkMapper; // 分块数据访问

    @Autowired
    private ChunkService chunkService;    // 分块服务

    @Autowired
    private LuceneService luceneService;   // 全文检索服务

    @Autowired
    private EmbeddingService embeddingService; // 向量化服务

    /**
     * 手动录入法规文档
     */
    @PostMapping("/document/add")
    public Map<String, Object> addDocument(@RequestBody Map<String, String> params) {
        Map<String, Object> result = new HashMap<>();
        try {
            RegulationDocument doc = new RegulationDocument();
            doc.setTitle(params.get("title"));// 标题
            doc.setCategory(params.get("category"));// 分类
            doc.setOrg(params.get("org")); // 发布机构
            if (params.get("publishDate") != null && !params.get("publishDate").isEmpty()) {
                doc.setPublishDate(LocalDate.parse(params.get("publishDate")));// 发布日期
            }
            doc.setContent(params.get("content")); // 内容
            doc.setCharCount(params.get("content") != null ? params.get("content").length() : 0);// 字数统计
            doc.setStatus(0);// 状态：0=待索引
            docMapper.insert(doc);

            result.put("success", true);
            result.put("message", "法规录入成功");
            result.put("docId", doc.getId());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "录入失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 文件导入法规（支持 PDF/DOCX/TXT/JSON）
     */
    @PostMapping("/document/upload")
    public Map<String, Object> uploadDocument(@RequestParam("file") MultipartFile file,
                                               @RequestParam(value = "category", defaultValue = "其他") String category) {
        Map<String, Object> result = new HashMap<>();
        try {
            //文件名处理
            String originalName = file.getOriginalFilename();
            String fileName = originalName != null ? originalName.toLowerCase() : "";
            String content;

            if (fileName.endsWith(".pdf")) {
                // PDF 文本提取
                /*
                PDDocument：PDF 文档对象

                PDFTextStripper：提取纯文本的工具

                 getText()：提取所有文本内容
                 */
                try (PDDocument pdfDoc = PDDocument.load(file.getBytes())) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    content = stripper.getText(pdfDoc);
                }
            } else if (fileName.endsWith(".docx")) {
                // Word 文本提取
                /*
                WPFDocument：Word 文档对象

                XWPFParagraph：段落对象

                遍历所有段落，提取文本
                 */
                try (XWPFDocument docx = new XWPFDocument(file.getInputStream())) {
                    StringBuilder sb = new StringBuilder();
                    for (XWPFParagraph para : docx.getParagraphs()) {
                        sb.append(para.getText()).append("\n");
                    }
                    content = sb.toString();
                }
            } else {
                // TXT / JSON
                content = new String(file.getBytes(), "UTF-8");//指定UTF-8，防止乱码
            }

            if (content == null || content.trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "文件内容为空，无法导入");
                return result;
            }

            /* 提取纯文件名作为标题
            // 原始文件名：中国共产党纪律处分条例.pdf
           // 提取标题：中国共产党纪律处分条例
             */
            String title = originalName;
            int dotIdx = title.lastIndexOf('.');//找到最后一个点
            if (dotIdx > 0) title = title.substring(0, dotIdx);//截取点之前的部分

            RegulationDocument doc = new RegulationDocument();
            doc.setTitle(title);
            doc.setCategory(category);
            doc.setContent(content);
            doc.setCharCount(content.length());
            doc.setStatus(0);
            doc.setFilePath(originalName);
            docMapper.insert(doc);

            result.put("success", true);
            result.put("message", "文件导入成功");
            result.put("docId", doc.getId());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "导入失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 为单个文档构建索引（分块 + Lucene + 向量）
     */
    @PostMapping("/document/index/{docId}")
    public Map<String, Object> buildDocIndex(@PathVariable Long docId) {
        Map<String, Object> result = new HashMap<>();
        RegulationDocument doc = docMapper.selectById(docId);
        try {
            if (doc == null) {
                result.put("success", false);
                result.put("message", "文档不存在");
                return result;
            }

            // Step 1: 文本分块，先删除后创建：避免重复分块
            chunkService.deleteChunksByDocId(docId);
            List<com.party.regulation.entity.RegulationChunk> chunks = chunkService.chunkDocument(doc);

            // Step 2: 构建 Lucene 全文索引
            luceneService.buildIndex(doc, chunks);

            // Step 3: 构建向量索引
            embeddingService.buildVectorIndex(docId);

            // Step 4: 更新状态
            doc.setStatus(1);
            doc.setCharCount(doc.getContent().length());
            docMapper.updateById(doc);

            result.put("success", true);
            result.put("message", "索引构建成功");
            result.put("chunkCount", chunks.size());
            result.put("steps", new String[]{"文档解析", "文本清洗", "文本分块(" + chunks.size() + "块)",
                    "Lucene全文索引", "向量化(text-embedding-v3)", "向量持久化(MySQL)"});
        } catch (Exception e) {
            if (doc != null) {
                doc.setStatus(2);
                docMapper.updateById(doc);
            }
            result.put("success", false);
            result.put("message", "索引构建失败：" + e.getMessage());
            log.error("索引构建失败", e);
        }
        return result;
    }

    /**
     * 批量索引所有待索引文档
     * 支持强制模式：force=true 时重新索引所有文档（包括 status=1 的）
     */
    @PostMapping("/document/index/batch")
    public Map<String, Object> batchIndex(@RequestBody(required = false) Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();
        try {
            boolean force = params != null && Boolean.TRUE.equals(params.get("force"));

            List<RegulationDocument> pendingDocs;
            if (force) {
                // 强制模式：索引所有文档
                pendingDocs = docMapper.selectList(null);
            } else {
                // 正常模式：只索引待索引文档
                pendingDocs = docMapper.selectList(
                        new LambdaQueryWrapper<RegulationDocument>().eq(RegulationDocument::getStatus, 0));
            }

            if (pendingDocs.isEmpty()) {
                result.put("success", true);
                result.put("total", 0);
                result.put("message", "没有需要索引的文档");
                return result;
            }

            int success = 0, fail = 0;//记录成功、失败、标题、分块总数
            List<String> titles = new ArrayList<>();
            int totalChunks = 0;

            for (int i = 0; i < pendingDocs.size(); i++) {
                RegulationDocument doc = pendingDocs.get(i);
                try {
                    // Step 1: 文本分块
                    chunkService.deleteChunksByDocId(doc.getId());
                    List<com.party.regulation.entity.RegulationChunk> chunks = chunkService.chunkDocument(doc);

                    // Step 2: 构建 Lucene 全文索引
                    luceneService.buildIndex(doc, chunks);

                    // Step 3: 构建向量索引（调用 Embedding API）
                    embeddingService.buildVectorIndex(doc.getId());

                    // Step 4: 更新状态
                    doc.setStatus(1);
                    doc.setCharCount(doc.getContent().length());
                    docMapper.updateById(doc);

                    success++;
                    totalChunks += chunks.size();
                    titles.add(doc.getTitle());
                    log.info("批量索引进度: {}/{}, 文档[{}]完成, 分块{}个",
                            i + 1, pendingDocs.size(), doc.getTitle(), chunks.size());

                    // 间隔800ms，避免API限流
                    Thread.sleep(800);
                } catch (Exception e) {
                    fail++;
                    log.warn("批量索引失败: {} - {}", doc.getTitle(), e.getMessage());
                    doc.setStatus(2);
                    docMapper.updateById(doc);
                }
            }

            result.put("success", true);
            result.put("total", pendingDocs.size());
            result.put("successCount", success);
            result.put("failCount", fail);
            result.put("totalChunks", totalChunks);
            result.put("titles", titles);
            result.put("message", "批量索引完成：" + success + " 篇成功，"
                    + totalChunks + " 个分块" + (fail > 0 ? "，" + fail + " 篇失败" : ""));
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }
}
