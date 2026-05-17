package com.party.regulation.service;

import com.party.regulation.config.IndexProperties;
import com.party.regulation.entity.RegulationChunk;
import com.party.regulation.entity.RegulationDocument;
import com.party.regulation.mapper.RegulationChunkMapper;
import com.party.regulation.mapper.RegulationDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本分块服务
 * 将法规全文切分为适合检索和 Embedding 的小块
 * 	文本分块：将长文档切分为固定大小的 chunk（500字符，重叠100）
 */
@Slf4j
@Service
public class ChunkService {

    @Autowired
    private IndexProperties indexProps;

    @Autowired
    private RegulationChunkMapper chunkMapper;

    /**
     * 对法规文档进行文本分块
     */
    public List<RegulationChunk> chunkDocument(RegulationDocument doc) {
        List<RegulationChunk> chunks = new ArrayList<>();
        String content = doc.getContent();
        if (content == null || content.trim().isEmpty()) return chunks;

        int chunkSize = indexProps.getChunkSize();
        int overlap = indexProps.getChunkOverlap();

        // 按章节分割
        List<String> sections = splitByChapter(content);
        String currentText = "";
        String currentChapter = "";

        for (String section : sections) {
            // 提取章节标题
            String chapterTitle = extractChapterTitle(section);
            if (chapterTitle != null && !chapterTitle.isEmpty()) {
                currentChapter = chapterTitle;
                section = section.substring(chapterTitle.length()).trim();
            }

            if (currentText.length() + section.length() > chunkSize && !currentText.isEmpty()) {
                // 当前文本达到分块大小，进行分块
                List<String> subChunks = splitBySize(currentText, chunkSize, overlap);
                for (int i = 0; i < subChunks.size(); i++) {
                    RegulationChunk chunk = new RegulationChunk();
                    chunk.setDocId(doc.getId());
                    chunk.setChunkIndex(chunks.size());
                    chunk.setContent(subChunks.get(i));
                    chunk.setCharCount(subChunks.get(i).length());
                    chunk.setChapter(currentChapter);
                    chunkMapper.insert(chunk);
                    chunks.add(chunk);
                }
                currentText = section;
            } else {
                currentText += (currentText.isEmpty() ? "" : "\n") + section;
            }
        }

        // 处理剩余文本
        if (!currentText.trim().isEmpty()) {
            List<String> subChunks = splitBySize(currentText, chunkSize, overlap);
            for (String sub : subChunks) {
                RegulationChunk chunk = new RegulationChunk();
                chunk.setDocId(doc.getId());
                chunk.setChunkIndex(chunks.size());
                chunk.setContent(sub);
                chunk.setCharCount(sub.length());
                chunk.setChapter(currentChapter);
                chunkMapper.insert(chunk);
                chunks.add(chunk);
            }
        }

        log.info("文档[{}]分块完成，共{}个分块", doc.getTitle(), chunks.size());
        return chunks;
    }

    /**
     * 按章节标题分割文本
     */
    private List<String> splitByChapter(String content) {
        List<String> sections = new ArrayList<>();
        // 匹配 "第X章" 或 "第X条" 或 "一、" 等标题
        Pattern pattern = Pattern.compile("(?=第[一二三四五六七八九十百千]+[章条节])");
        Matcher matcher = pattern.matcher(content);
        int lastStart = 0;
        while (matcher.find()) {
            if (matcher.start() > lastStart) {
                sections.add(content.substring(lastStart, matcher.start()).trim());
            }
            lastStart = matcher.start();
        }
        if (lastStart < content.length()) {
            sections.add(content.substring(lastStart).trim());
        }
        if (sections.isEmpty()) {
            sections.add(content);
        }
        return sections;
    }

    /**
     * 按固定大小分割文本（带重叠）
     */
    private List<String> splitBySize(String text, int chunkSize, int overlap) {
        List<String> result = new ArrayList<>();
        if (text.length() <= chunkSize) {
            result.add(text);
            return result;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            result.add(text.substring(start, end));
            if (end >= text.length()) break;
            start = end - overlap;
        }
        return result;
    }

    /**
     * 提取章节标题
     */
    private String extractChapterTitle(String text) {
        Pattern pattern = Pattern.compile("^[第][一二三四五六七八九十百千]+[章条节][\\s\\S]{0,50}");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group().trim();
        }
        return "";
    }

    /**
     * 删除某文档的所有分块
     */
    public void deleteChunksByDocId(Long docId) {
        chunkMapper.deleteByDocId(docId);
    }
}
