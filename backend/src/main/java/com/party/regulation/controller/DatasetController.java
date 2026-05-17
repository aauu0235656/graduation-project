package com.party.regulation.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.party.regulation.entity.RegulationChunk;
import com.party.regulation.entity.RegulationDocument;
import com.party.regulation.entity.SystemConfig;
import com.party.regulation.mapper.*;
import com.party.regulation.service.ChunkService;
import com.party.regulation.service.EmbeddingService;
import com.party.regulation.service.LuceneService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 数据集管理 （知识库管理）Controller，文档的 CRUD、导入、导出，直接操作 Mapper + 调用 LuceneService/EmbeddingService
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class DatasetController {

    @Autowired
    private RegulationDocumentMapper docMapper;  //文档数据访问

    @Autowired
    private RegulationChunkMapper chunkMapper;   //分块数据访问

    @Autowired
    private QaHistoryMapper qaHistoryMapper;    //问答历史数据访问

    @Autowired
    private SystemConfigMapper configMapper;     // 系统配置数据访问

    @Autowired
    private LuceneService luceneService;        // 全文检索服务

    @Autowired
    private EmbeddingService embeddingService;  // 向量化服务

    /**
     * 获取文档列表（支持分页、搜索、筛选）
     */
    @GetMapping("/documents")
    public Map<String, Object> getDocuments(
            @RequestParam(required = false, defaultValue = "1") int page,//页码
            @RequestParam(required = false, defaultValue = "10") int size,//每页数量
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer status) {

        Map<String, Object> result = new HashMap<>();
        try {
            LambdaQueryWrapper<RegulationDocument> wrapper = new LambdaQueryWrapper<>();//LambdaQueryWrapper 的作用：构建动态查询条件
            if (keyword != null && !keyword.isEmpty()) {//等价于WHERE (title LIKE '%keyword%' OR content LIKE '%keyword%'
                wrapper.and(w -> w.like(RegulationDocument::getTitle, keyword)
                        .or().like(RegulationDocument::getContent, keyword));
            }
            if (category != null && !category.isEmpty() && !category.equals("all")) {//AND category = 'category'
                wrapper.eq(RegulationDocument::getCategory, category);
            }
            if (status != null) {//状态筛选
                wrapper.eq(RegulationDocument::getStatus, status);
            }
            wrapper.orderByDesc(RegulationDocument::getId);//按ID降序：最新的文档排前面

            long total = docMapper.selectCount(wrapper);//查询总数
            List<RegulationDocument> docs = docMapper.selectList(
                    wrapper.last("LIMIT " + size + " OFFSET " + (page - 1) * size));//计算返还记录

            // 附加每个文档的分块数
            List<Map<String, Object>> docList = new ArrayList<>();
            for (RegulationDocument doc : docs) {
                Map<String, Object> docMap = new HashMap<>();
                docMap.put("id", doc.getId());
                docMap.put("title", doc.getTitle());
                docMap.put("category", doc.getCategory());
                docMap.put("org", doc.getOrg());
                docMap.put("publishDate", doc.getPublishDate() != null ? doc.getPublishDate().toString() : "");
                docMap.put("charCount", doc.getCharCount());
                docMap.put("status", doc.getStatus());
                docMap.put("filePath", doc.getFilePath());
                docMap.put("createTime", doc.getCreateTime());
                docMap.put("chunkCount", chunkMapper.countByDocId(doc.getId()));
                docList.add(docMap);
            }

            result.put("success", true);
            result.put("total", total);
            result.put("page", page);
            result.put("size", size);
            result.put("documents", docList);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 获取单个文档详情（含分块内容）
     */
    @GetMapping("/document/{docId}")
    public Map<String, Object> getDocument(@PathVariable Long docId) {
        Map<String, Object> result = new HashMap<>();
        try {
            RegulationDocument doc = docMapper.selectById(docId);
            if (doc == null) {
                result.put("success", false);
                result.put("message", "文档不存在");
                return result;
            }
            List<RegulationChunk> chunks = chunkMapper.selectByDocId(docId);//分块信息：包含文档的具体内容片段

            result.put("success", true);
            result.put("document", doc);
            result.put("chunks", chunks);
            result.put("chunkCount", chunks.size());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/document/{docId}")//HTTP 方法：DELETE，表示删除资源
    public Map<String, Object> deleteDocument(@PathVariable Long docId) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 删除分块
            chunkMapper.deleteByDocId(docId);
            // 删除 Lucene 索引
            luceneService.deleteByDocId(docId);
            // 删除向量索引
            embeddingService.deleteByDocId(docId);
            // 删除文档
            docMapper.deleteById(docId);

            result.put("success", true);
            result.put("message", "文档已删除");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "删除失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 获取系统统计
     */
    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        Map<String, Object> result = new HashMap<>();
        try {
            result.put("success", true);
            result.put("docCount", docMapper.countAll());//文档数量
            result.put("chunkCount", chunkMapper.countAll());//分块数量统计
            result.put("indexedCount", docMapper.countByStatus(1));//已索引的文档数
            result.put("pendingCount", docMapper.countByStatus(0));//待索引文档数
            result.put("qaCount", qaHistoryMapper.selectCount(null));//问答历史数
            result.put("luceneDocCount", luceneService.getDocCount());//Lucene 索引文档数
            result.put("vectorCount", embeddingService.getVectorCount());// 向量索引数
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 获取系统配置
     */
    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> result = new HashMap<>();
        try {
            List<SystemConfig> configs = configMapper.selectList(null);//SQL: SELECT * FROM system_config
            Map<String, String> configMap = new HashMap<>();
            for (SystemConfig c : configs) {
                // 敏感信息脱敏
                String val = c.getConfigValue();
                if (c.getConfigKey().contains("key") && val != null && val.length() > 8) {
                    val = val.substring(0, 4) + "****" + val.substring(val.length() - 4);
                }
                configMap.put(c.getConfigKey(), val);
            }
            result.put("success", true);
            result.put("config", configMap);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 更新系统配置
     */
    @PostMapping("/config")
    public Map<String, Object> updateConfig(@RequestBody Map<String, String> params) {
        Map<String, Object> result = new HashMap<>();
        try {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                LambdaQueryWrapper<SystemConfig> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(SystemConfig::getConfigKey, entry.getKey());
                SystemConfig config = configMapper.selectOne(wrapper);
                if (config != null) {
                    config.setConfigValue(entry.getValue());
                    configMapper.updateById(config);
                }
            }
            result.put("success", true);
            result.put("message", "配置更新成功");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }
}
