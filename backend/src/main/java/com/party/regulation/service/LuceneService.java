package com.party.regulation.service;

import com.party.regulation.entity.RegulationChunk;
import com.party.regulation.entity.RegulationDocument;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.highlight.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

//Lucene 全文索引的构建（建索引、写文档）和检索（BM25 评分）
@Slf4j
@Service
public class LuceneService {

    @Value("${index.lucene-path}")
    private String lucenePath;//索引存储路径

    private Analyzer analyzer;//文本分析器
    private Directory directory;//索引存储目录
    private IndexWriter indexWriter;//索引写入器

    @PostConstruct
    public void init() throws IOException {
        analyzer = new SmartChineseAnalyzer(); // 创建中文分词器
        directory = FSDirectory.open(Paths.get(lucenePath));// 打开磁盘目录
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);// 有则追加，无则创建
        indexWriter = new IndexWriter(directory, config);//创建索引写入器
        log.info("Lucene 索引服务初始化完成，路径：{}", lucenePath);
    }

    @PreDestroy
    public void destroy() throws IOException {
        if (indexWriter != null) indexWriter.close();
        if (directory != null) directory.close();
        if (analyzer != null) analyzer.close();
    }

    /**
     * 为法规分块构建全文索引，将每个文档分成多个 RegulationChunk（分块）
     */
    public void buildIndex(RegulationDocument doc, List<RegulationChunk> chunks) throws IOException {
        for (RegulationChunk chunk : chunks) {
            Document luceneDoc = new Document();
            luceneDoc.add(new LongPoint("chunkId", chunk.getId()));//分块ID(可搜索)
            luceneDoc.add(new StoredField("chunkId", chunk.getId()));//可返回
            luceneDoc.add(new LongPoint("docId", doc.getId()));//文档ID
            luceneDoc.add(new StoredField("docId", doc.getId()));
            luceneDoc.add(new TextField("title", doc.getTitle(), Field.Store.YES));//标题
            luceneDoc.add(new TextField("content", chunk.getContent(), Field.Store.YES));//正文内容
            luceneDoc.add(new TextField("category", doc.getCategory() != null ? doc.getCategory() : "", Field.Store.YES));//分类标签
            // 不分词的 category 字段，用于精确类别筛选（TextField：用于模糊搜索，StringField：用于精确匹配）
            if (doc.getCategory() != null && !doc.getCategory().isEmpty()) {
                luceneDoc.add(new StringField("categoryExact", doc.getCategory(), Field.Store.NO));//精确分类
            }
            luceneDoc.add(new StoredField("chapter", chunk.getChapter() != null ? chunk.getChapter() : ""));
            luceneDoc.add(new StoredField("chunkIndex", chunk.getChunkIndex()));
            indexWriter.addDocument(luceneDoc);//添加文档索引，相当于数据库insert
        }
        indexWriter.commit();
        log.info("文档[{}]的{}个分块已写入Lucene索引", doc.getTitle(), chunks.size());
    }

    /**
     * 删除某文档的所有索引
     */
    public void deleteByDocId(Long docId) throws IOException {
        indexWriter.deleteDocuments(LongPoint.newExactQuery("docId", docId));//删除文件（查询条件：docId 字段等于指定的值）
        indexWriter.commit();
        log.info("文档ID[{}]的Lucene索引已删除", docId);
    }

    /**
     * 基于关键词的全文检索
     * @return 检索结果列表，包含高亮片段
     */
    public List<Map<String, Object>> search(String keyword, String category, int topK) throws Exception {
        if (keyword == null || keyword.trim().isEmpty()) return new ArrayList<>();//关键词为空时直接返回空列表

        try (IndexReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);

            // 多字段搜索：标题权重高
            Map<String, Float> boosts = new HashMap<>();
            boosts.put("title", 3.0f);//标题相关度
            boosts.put("content", 1.0f);//正文相关度
            boosts.put("category", 1.5f);//分类字段相关度

            //MultiFieldQueryParser：同时在多个字段中搜索
            MultiFieldQueryParser parser = new MultiFieldQueryParser(
                    new String[]{"title", "content", "category"}, analyzer, boosts);
            parser.setDefaultOperator(QueryParser.Operator.OR);
            Query query = parser.parse(keyword);

            // 类别过滤，只在某个类别里搜，
            boolean useCategoryExact = false;
            if (category != null && !category.isEmpty() && !category.equals("all")) {
                // 检查索引是否有 categoryExact 字段（新索引有，旧索引没有）
                boolean hasCategoryExact = false;
                for (LeafReaderContext ctx : reader.leaves()) {
                    FieldInfos infos = ctx.reader().getFieldInfos();
                    if (infos.fieldInfo("categoryExact") != null) {
                        hasCategoryExact = true;
                        break;
                    }
                }
                if (hasCategoryExact) {
                    /* 新索引：用不分词字段精确匹配，新索引：精确查询过滤
                    MUST (title:"纪律处分" OR content:"纪律处分")
                    AND
                    MUST categoryExact:"纪律法规"
                     */
                    Query categoryQuery = new TermQuery(new Term("categoryExact", category));
                    BooleanQuery.Builder boolBuilder = new BooleanQuery.Builder();
                    boolBuilder.add(query, BooleanClause.Occur.MUST);
                    boolBuilder.add(categoryQuery, BooleanClause.Occur.MUST);
                    query = boolBuilder.build();
                    useCategoryExact = true;
                }
                // 旧索引没有 categoryExact 字段，在结果中做 category 后过滤
            }

            TopDocs topDocs = searcher.search(query, useCategoryExact ? topK : topK * 3);//执行搜索，有精确过滤：直接取 topK，无精确过滤：先取 3×topK，再后过滤
            Highlighter highlighter = createHighlighter();

            List<Map<String, Object>> results = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.doc(scoreDoc.doc);

                // 旧索引：对 category 做后过滤
                if (!useCategoryExact && category != null && !category.isEmpty() && !category.equals("all")) {
                    String docCategory = doc.get("category");
                    if (docCategory == null || !docCategory.contains(category)) {
                        continue;
                    }
                }
                Map<String, Object> result = new LinkedHashMap<>();
                // StoredField numeric values come back as String via doc.get(), parse to Long for consistency
                result.put("docId", Long.parseLong(doc.get("docId")));
                result.put("chunkId", Long.parseLong(doc.get("chunkId")));
                result.put("chunkIndex", Integer.parseInt(doc.get("chunkIndex")));
                result.put("title", doc.get("title"));
                result.put("category", doc.get("category"));
                result.put("chapter", doc.get("chapter"));
                result.put("content", doc.get("content"));
                result.put("score", scoreDoc.score);

                // 高亮标题和内容
                try {
                    String hlTitle = highlighter.getBestFragment(analyzer, "title", doc.get("title"));
                    result.put("highlightTitle", hlTitle != null ? hlTitle : doc.get("title"));
                    String hlContent = highlighter.getBestFragment(analyzer, "content", doc.get("content"));
                    result.put("highlightContent", hlContent != null ? hlContent :
                            doc.get("content").substring(0, Math.min(200, doc.get("content").length())) + "...");
                } catch (Exception e) {
                    result.put("highlightTitle", doc.get("title"));
                    result.put("highlightContent", doc.get("content").substring(0, Math.min(200, doc.get("content").length())));
                }
                results.add(result);
            }
            return results;
        }
    }

    //创建高亮器
    private Highlighter createHighlighter() {
        org.apache.lucene.search.highlight.Formatter formatter = new SimpleHTMLFormatter("<em>", "</em>");//高亮标签：<em>关键词</em>
        QueryScorer scorer = new QueryScorer(new TermQuery(new Term("content", "")));
        Highlighter highlighter = new Highlighter(formatter, scorer);
        highlighter.setTextFragmenter(new SimpleSpanFragmenter(scorer, 200));//每个高亮片段最多200字符
        return highlighter;
    }

    public long getDocCount() throws IOException {
        return indexWriter.getDocStats().numDocs;
    }
}
/*
返回结果样例：
 "docId": "1001",
    "chunkId": "2001",
    "chunkIndex": 0,
    "title": "中国共产党纪律处分条例",
    "category": "纪律法规",
    "chapter": "第一章 总则",
    "content": "第一条 为了维护党的纪律，纯洁党的组织...",
    "score": 8.765432,
    "highlightTitle": "中国<em>共产党纪律处分</em>条例",
    "highlightContent": "第一条 为了维护党的<em>纪律</em>..."
 */