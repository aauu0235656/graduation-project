package com.party.regulation.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用网页爬虫服务 + 学习强国专用爬取
 * 支持传入任意 URL，自动提取：标题、发布时间、来源、正文
 *crawlXuexiByUrl(url)
 *     ↓
 * 尝试1：从URL提取 ?id=13226399 → crawlXuexiByArticleId()
 *     ↓ 失败
 * 尝试2：32位十六进制hash → fetchXuexiDataJs()（调用 /data{hash}.js 接口）
 *     ↓ 失败
 * 尝试3：匹配 /{hash}.html → 转换为 /data{hash}.js
 *     ↓ 都失败
 * 降级：crawlPage() 通用网页爬取
 */
@Slf4j
@Service
public class CrawlerService {

    private static final Pattern DATE_PATTERN = Pattern.compile(//匹配常见的中文日期格式
            "(\\d{4})[年\\-/](\\d{1,2})[月\\-/](\\d{1,2})日?"
    );

    private static final String[] SOURCE_KEYWORDS = {//标识文章来源字段的关键词
            "来源", "source", "出处", "发布机构", "发文机关"
    };

    private static final String[] CONTENT_SELECTORS = {//内容选择器
            "article",
            ".article-content", ".content", ".main-content",
            "#content", "#article", "#main",
            ".TRS_Editor", ".detail-content",
            "div[class*=content]", "div[class*=article]",
            "div[id*=content]", "div[id*=article]",
            ".news-content", ".text"
    };

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS) // 连接超时15秒
            .readTimeout(30, TimeUnit.SECONDS)   // 读取超时30秒
            .followRedirects(true) // 自动跟随重定向
            .build();

    // ==================== 学习强国专用爬取 ====================

    /**
     * 通过文章 ID 爬取学习强国文章（调用 boot-source 接口） 学习强国API直接调用
     *
     * @param articleId 文章ID，从URL中提取，如 lgpage/detail/index.html?id=13226399 中的 13226399
     * @return Map 包含 title/publishDate/source/content/url
     */
    public Map<String, String> crawlXuexiByArticleId(String articleId) throws IOException {
        String apiUrl = "https://boot-source.xuexi.cn/data/app/" + articleId + ".js";//// 构建API URL
        log.info("学习强国接口爬取: {}", apiUrl);

        Request request = new Request.Builder()// 发送HTTP请求
                .url(apiUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://www.xuexi.cn/")
                .get()
                .build();

        String responseBody;
        // // 解析JSON响应
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("学习强国接口请求失败: " + response.code());
            }
            responseBody = response.body() != null ? response.body().string() : "";
        }

        // 接口返回格式可能是 "callback({...})" 或 "callback({...});" 或纯 JSON
        String jsonStr = stripJsonpWrapper(responseBody);

        JSONObject data = JSON.parseObject(jsonStr);
        return parseXuexiArticle(data, articleId);
    }

    /**
     * 从学习强国URL中提取文章ID并爬取
     * 支持格式：
     *   https://www.xuexi.cn/lgpage/detail/index.html?id=13226399&item_id=13226399
     *   https://www.xuexi.cn/d3c74c0dc03d6db012934c53bc33196f/9b0f04ec6509904be734f5f609a3604a.html
     *
     * @param url 学习强国文章URL
     * @return 文章数据
     * 智能URL识别
     */
    public Map<String, String> crawlXuexiByUrl(String url) throws IOException {
        log.info("学习强国URL爬取: {}", url);

        // 1. 尝试从URL提取 id 参数（支持纯数字和32位十六进制字符串）
        Matcher idMatcher = Pattern.compile("[?&]id=([a-fA-F0-9]+)").matcher(url);//提取ID参数
        if (idMatcher.find()) {
            String id = idMatcher.group(1);
            // 纯数字ID → 走 boot-source 接口（ crawlXuexiByArticleId() ）
            if (id.matches("\\d+")) {
                return crawlXuexiByArticleId(id);
            }
            // 32位十六进制ID → 尝试 data{id}.js 接口，失败则走通用爬取兜底，fetchXuexiDataJs()
            if (id.length() == 32) {
                try {
                    String dataUrl = "https://www.xuexi.cn/data" + id + ".js";
                    return fetchXuexiDataJs(dataUrl, url);
                } catch (Exception e) {
                    log.warn("32位ID的data接口无法获取，降级为通用爬取: {}", url);
                    // 继续走下面的通用爬取
                }
            }
        }

        // 2. 尝试旧格式：/{md5_hash}.html → data{md5}.js，调用 fetchXuexiDataJs()
        Matcher htmlMatcher = Pattern.compile("/([a-f0-9]{32})\\.html$").matcher(url);
        if (htmlMatcher.find()) {
            String dataUrl = url.replace("/" + htmlMatcher.group(1) + ".html",
                    "/data" + htmlMatcher.group(1) + ".js");
            return fetchXuexiDataJs(dataUrl, url);
        }

        // 3. 都匹配不上，尝试先请求原页面再分析， 降级到通用爬取 crawlPage()
        return crawlPage(url);
    }

    /**
     * 爬取学习强国列表页，获取文章列表
     * 列表URL格式如: https://www.xuexi.cn/{folder}/{md5_hash}.html
     * 转换为: https://www.xuexi.cn/{folder}/data{md5_hash}.js
     *
     * @param listUrl  列表页URL
     * @param maxCount 最多爬取条数
     * @return 文章列表
     */
    public List<Map<String, String>> crawlXuexiList(String listUrl, int maxCount) throws IOException {
        log.info("学习强国列表爬取: {}, 最多{}条", listUrl, maxCount);

        // 将 .html 转为 .js 格式获取数据
        String dataUrl;
        Matcher m = Pattern.compile("/([a-f0-9]{32})\\.html$").matcher(listUrl);
        if (m.find()) {
            dataUrl = listUrl.replace("/" + m.group(1) + ".html",
                    "/data" + m.group(1) + ".js");
        } else if (listUrl.contains(".json")) {
            dataUrl = listUrl;
        } else {
            throw new IOException("无法识别的学习强国列表URL格式，请确认URL正确");
        }
        //调用数据接口
        Request request = new Request.Builder()
                .url(dataUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36") //模拟浏览器访问，避免被识别为爬虫
                .header("Referer", "https://www.xuexi.cn/") // 设置来源
                .get()
                .build();

        String responseBody;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("学习强国列表接口请求失败: " + response.code());
            }
            responseBody = response.body() != null ? response.body().string() : "";
        }


        //解析JSON响应
        String jsonStr = stripJsonpWrapper(responseBody);

        JSONObject listData = JSON.parseObject(jsonStr);
        List<Map<String, String>> articles = new ArrayList<>();

        // 尝试多种列表数据结构
        JSONArray items = null;
        //直接是数组
        if (listData.containsKey("data")) {
            Object dataObj = listData.get("data");
            if (dataObj instanceof JSONArray) {
                items = (JSONArray) dataObj;
            } else if (dataObj instanceof JSONObject) {
                // 情况2：嵌套对象
                JSONObject data = (JSONObject) dataObj;
                // 尝试常见字段名
                for (String key : new String[]{"list", "items", "content", "records"}) {
                    if (data.containsKey(key) && data.get(key) instanceof JSONArray) {
                        items = data.getJSONArray(key);
                        break;
                    }
                }
            }
        }
        // 情况3：直接是list字段
        if (items == null && listData.containsKey("list")) {
            items = listData.getJSONArray("list");
        }
        // 情况4：直接是content字段
        if (items == null && listData.containsKey("content")) {
            Object content = listData.get("content");
            if (content instanceof JSONArray) {
                items = (JSONArray) content;
            }
        }

        if (items == null) {
            log.warn("未从列表接口中找到文章数据，尝试用通用方式提取链接");
            // 降级：用通用方式爬取列表页中的链接
            return crawlList(listUrl, maxCount);
        }
        // 遍历爬取逻辑
        int count = 0;
        for (int i = 0; i < items.size() && count < maxCount; i++) //不超过列表长度，不超过最大爬取数量
             {
            try {
                // 获取列表项
                JSONObject item = items.getJSONObject(i);

                // 提取文章URL
                String articleUrl = extractArticleUrl(item);
                String title = item.getString("title");

                // 跳过无效URL
                if (articleUrl == null || articleUrl.isEmpty()) continue;

                // 选择爬取策略
                Map<String, String> article;
                if (articleUrl.contains("xuexi.cn")) {
                    article = crawlXuexiByUrl(articleUrl);//专用爬取通道
                } else {
                    article = crawlPage(articleUrl);
                }

                // 质量检查
                if (article.get("content").length() > 100) {
                    articles.add(article);
                    count++;
                }
                Thread.sleep(800);
            } catch (Exception e) {
                log.warn("列表中第{}项爬取失败: {}", i, e.getMessage());
            }
        }

        log.info("学习强国列表爬取完成，共获取 {} 篇文章", articles.size());
        return articles;
    }

    /** 从列表项中提取文章URL */
    private String extractArticleUrl(JSONObject item) {
        // 常见的URL字段名
        String[] urlFields = {"url", "link", "target", "articleUrl",
                "jumpUrl", "shareUrl", "pcUrl", "urlPath"};
        for (String field : urlFields) {
            String val = item.getString(field);
            if (val != null && !val.isEmpty()) return val;
        }
        /* 尝试从其他嵌套结构中提取
        有些JSON结构是嵌套的：
 {
    "title": "文章标题",
    "content": {           // 嵌套对象
        "url": "https://...",
        "text": "文章内容"
    }
}
        */
        for (String key : new String[]{"content", "data", "item"}) {
            Object obj = item.get(key);
            if (obj instanceof JSONObject) {
                JSONObject sub = (JSONObject) obj;
                for (String field : urlFields) {
                    String val = sub.getString(field);
                    if (val != null && !val.isEmpty()) return val;
                }
            }
        }
        return null;
    }

    /** 解析学习强国文章数据 JSON 负责将API返回的原始JSON数据转换为结构化的、安全的文章数据。*/
    private Map<String, String> parseXuexiArticle(JSONObject data, String articleId) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("url", "https://www.xuexi.cn/lgpage/detail/index.html?id=" + articleId);

        // 标题
        String title = data.getString("title");
        if (title == null || title.isEmpty()) {
            title = data.getString("name");
        }
        result.put("title", title != null ? title : "未知标题");

        // 发布时间
        String date = "";
        if (data.containsKey("publishTime")) {
            date = formatDate(data.getLong("publishTime"));
        } else if (data.containsKey("ptime")) {
            date = formatDate(data.getLong("ptime"));
        } else if (data.containsKey("createTime")) {
            date = formatDate(data.getLong("createTime"));
        } else if (data.containsKey("date")) {
            date = data.getString("date");
        }
        result.put("publishDate", date != null ? date : "");

        // 来源
        String source = data.getString("source");
        if (source == null || source.isEmpty()) {
            source = data.getString("from");
        }
        if (source == null || source.isEmpty()) {
            source = data.getString("editor");
        }
        result.put("source", source != null ? source : "学习强国");

        // 正文 - 尝试多种字段名
        String content = "";
        // 尝试从content字段提取
        if (data.containsKey("content")) {
            Object c = data.get("content");
            if (c instanceof String) {
                content = (String) c;// 直接是字符串
            } else if (c instanceof JSONObject) {
                // 是JSON对象，再找里面的content字段
                JSONObject contentObj = (JSONObject) c;
                content = contentObj.getString("content");
                if (content == null)
                    // 如果没有content字段，将整个对象转为JSON字符串
                    content = contentObj.toJSONString();
            }
        }
        // 如果content字段没有，尝试其他字段
        if (content.isEmpty() && data.containsKey("body")) {
            content = data.getString("body");
        }
        if (content.isEmpty() && data.containsKey("text")) {
            content = data.getString("text");
        }
        if (content.isEmpty() && data.containsKey("desc")) {
            content = data.getString("desc");
        }

        // 如果 content 是 HTML，保留安全排版标签，同时提取纯文本
        if (content.contains("<")) {
            Document contentDoc = Jsoup.parse(content);
            // 移除图片、音视频、脚本、样式、链接等非文字元素
            contentDoc.select("img, image, video, audio, embed, iframe, figure, svg, " +
                    "script, style, link, meta, form, input, button").remove();
            // 去掉所有 href / src / on* 属性，防止 XSS
            contentDoc.select("*").forEach(el -> {
                el.removeAttr("href");
                el.removeAttr("src");
                el.removeAttr("onclick");
                el.removeAttr("onload");
                el.removeAttr("onerror");
                el.removeAttr("style");
                el.removeAttr("class");
                el.removeAttr("id");
            });
            // 保留安全的排版 HTML（段落、标题、加粗、列表等）
            String safeHtml = contentDoc.body().html();
            result.put("htmlContent", safeHtml);
            // 纯文本供入库使用
            content = contentDoc.text();
        } else {
            // 纯文本：按段落转 HTML（双换行 → <p>）
            String htmlFromPlain = "<p>" + content.replace("\n\n", "</p><p>").replace("\n", "<br>") + "</p>";
            result.put("htmlContent", htmlFromPlain);
        }

        result.put("content", cleanContent(content));

        log.info("学习强国文章解析完成: title={}, date={}, contentLen={}",
                result.get("title"), result.get("publishDate"), result.get("content").length());
        return result;
    }

    /** 请求旧格式的 .js 数据接口 （https://www.xuexi.cn/data{hash}.js）*/
    private Map<String, String> fetchXuexiDataJs(String dataUrl, String originalUrl) throws IOException {
        Request request = new Request.Builder()
                .url(dataUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://www.xuexi.cn/")
                .get()
                .build();

        String responseBody;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("学习强国数据接口请求失败: " + response.code());
            }
            responseBody = response.body() != null ? response.body().string() : "";
        }

        // 检测是否返回了错误页面（而非JSON数据）
        if (responseBody.contains("<!DOCTYPE") || responseBody.contains("<html")) {
            throw new IOException("该URL不支持通过数据接口爬取，可能是图文专题或新格式页面");
        }

        //处理JSONP格式
        String jsonStr = stripJsonpWrapper(responseBody);

        //解析JSON并提取文章ID（从id字段提取，从article_id字段提取）
        JSONObject data = JSON.parseObject(jsonStr);
        String articleId = data.getString("id");
        if (articleId == null) articleId = data.getString("article_id");
        if (articleId == null) articleId = "unknown";
        return parseXuexiArticle(data, articleId);
    }

    //去除JSONP格式的函数包装
    private String stripJsonpWrapper(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        // 匹配 callback({...}) 或 callback({...}); 等JSONP格式
        if (trimmed.matches("^\\w+\\(.*\\)\\;?$")) {
            return trimmed.replaceAll("^\\w+\\(", "").replaceAll("\\)\\;?$", "");
        }//callback({"title":"测试"})->{"title":"测试"}
        return trimmed;
    }

    /** 时间戳（毫秒）转 yyyy-MM-dd 格式 */
    private String formatDate(Long timestamp) {
        if (timestamp == null) return "";
        try {
            // 13位毫秒时间戳
            if (timestamp > 9999999999L) {
                return new java.text.SimpleDateFormat("yyyy-MM-dd")
                        .format(new java.util.Date(timestamp));
            }
            // 10位秒时间戳
            return new java.text.SimpleDateFormat("yyyy-MM-dd")
                    .format(new java.util.Date(timestamp * 1000));
        } catch (Exception e) {
            return String.valueOf(timestamp);
        }
    }

    // ==================== 通用网页爬取（保留） ====================

    /**
     * 爬取单个页面，返回结构化数据
     */
    public Map<String, String> crawlPage(String url) throws IOException {
        log.info("开始爬取页面: {}", url);

        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Safari/537.36")//	模拟真实浏览器访问
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")//告诉服务器接受的内容类型
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")//获取中文页面
                .timeout(15000)//  15秒超时
                .followRedirects(true)
                .get();

        Map<String, String> result = new LinkedHashMap<>();
        result.put("url", url);
        result.put("title", extractTitle(doc));
        result.put("publishDate", extractDate(doc));
        result.put("source", extractSource(doc, url));
        result.put("content", extractContent(doc));

        log.info("爬取完成: title={}, date={}, source={}, contentLen={}",
                result.get("title"), result.get("publishDate"),
                result.get("source"), result.get("content").length());
        return result;
    }

    /**
     * 批量爬取列表页中的文章链接
     */
    public List<Map<String, String>> crawlList(String listUrl, int maxCount) throws IOException {
        log.info("开始爬取列表页: {}, 最多{}条", listUrl, maxCount);

        Document listDoc = Jsoup.connect(listUrl)//下载列表页
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(15000)
                .followRedirects(true)
                .get();

        //提取文章链接
        List<String> links = extractArticleLinks(listDoc, listUrl);
        log.info("从列表页提取到 {} 个链接", links.size());

        //逐个爬取文章
        List<Map<String, String>> articles = new ArrayList<>();
        int count = 0;
        for (String link : links) {
            if (count >= maxCount) break;
            try {
                Map<String, String> article = crawlPage(link);
                if (article.get("content").length() > 100) {
                    articles.add(article);
                    count++;
                }
                Thread.sleep(800);
            } catch (Exception e) {
                log.warn("爬取失败，跳过: {} - {}", link, e.getMessage());
            }
        }

        log.info("批量爬取完成，共获取 {} 篇文章", articles.size());
        return articles;
    }

    // ==================== 私有提取方法 ====================

    private String extractTitle(Document doc) {
        // 策略1：优先使用h1标签
        Element h1 = doc.selectFirst("h1");
        if (h1 != null && !h1.text().trim().isEmpty()) {
            return h1.text().trim();
        }
        // 策略2：尝试常见标题选择器
        String[] titleSelectors = {
                ".article-title", ".title", "#title",
                ".news-title", ".detail-title", "h2"
        };
        for (String sel : titleSelectors) {
            Element el = doc.selectFirst(sel);
            if (el != null && !el.text().trim().isEmpty()) {
                return el.text().trim();
            }
        }
        // 策略3：使用页面title标签
        String pageTitle = doc.title();
        if (pageTitle != null && !pageTitle.isEmpty()) {
            return pageTitle.replaceAll("[_|－\\-–—].*$", "").trim();
        }
        return "未知标题";
    }

    private String extractDate(Document doc) {
        String[] dateSelectors = {
                ".publish-date", ".date", ".time", ".pubdate",
                ".article-date", ".news-date", "time",
                "[class*=date]", "[class*=time]"
        };
        for (String sel : dateSelectors) {
            Element el = doc.selectFirst(sel);
            if (el != null) {
                String text = el.hasAttr("datetime") ? el.attr("datetime") : el.text();
                String date = matchDate(text);
                if (date != null) return date;
            }
        }
        // 在正文中搜索日期
        String bodyText = doc.body() != null ? doc.body().text() : "";
        String date = matchDate(bodyText);
        if (date != null) return date;
        return "";
    }

    private String extractSource(Document doc, String url) {
        // 策略1：从CSS类提取
        String[] sourceSelectors = {
                ".source", ".from", ".article-source", ".news-source",
                "[class*=source]", "[class*=origin]"
        };
        for (String sel : sourceSelectors) {
            Element el = doc.selectFirst(sel);
            if (el != null && !el.text().trim().isEmpty()) {
                String text = el.text().trim();
                for (String kw : SOURCE_KEYWORDS) {
                    text = text.replaceAll("^" + kw + "[：:：]?\\s*", "");
                }
                if (!text.isEmpty()) return text;
            }
        }
        // 策略2：从meta标签提取
        Element metaSite = doc.selectFirst("meta[name=site_name]");
        if (metaSite != null) return metaSite.attr("content");
        Element metaAuthor = doc.selectFirst("meta[name=author]");
        if (metaAuthor != null) return metaAuthor.attr("content");
        // 策略3：从URL提取域名
        try {
            java.net.URL u = new java.net.URL(url);
            return u.getHost().replaceAll("^www\\.", "");
        } catch (Exception e) {
            return "";
        }
    }

    private String extractContent(Document doc) {
        // 1. 移除无关元素
        doc.select("script, style, nav, header, footer, aside, " +
                ".ad, .ads, .advertisement, .sidebar, .menu, " +
                ".comment, .comments, .share, .related, " +
                "img, image, video, audio, embed, iframe, figure, svg").remove();
       // 2. 尝试常见内容选择器
        for (String selector : CONTENT_SELECTORS) {
            Element el = doc.selectFirst(selector);
            if (el != null) {
                String text = el.text().trim();
                if (text.length() > 200) {
                    return cleanContent(text);
                }
            }
        }
        // 3. 智能查找：文本密度最高的元素
        Element densestDiv = findDensestElement(doc);
        if (densestDiv != null) {
            return cleanContent(densestDiv.text().trim());
        }
        // 4. 最终回退：整个页面body
        return cleanContent(doc.body() != null ? doc.body().text() : "");
    }

    private Element findDensestElement(Document doc) {
        Elements divs = doc.select("div, section, main");
        Element best = null;
        int maxLen = 0;
        for (Element div : divs) {
            String ownText = div.ownText().trim();//只算自己的文本
            String text = div.text().trim(); // 所有文本（包括子元素）
            if (text.length() > maxLen && ownText.length() > 50) {
                maxLen = text.length();
                best = div;
            }
        }
        return best;
    }

    private List<String> extractArticleLinks(Document doc, String baseUrl) {//提取文章链接
        List<String> links = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 提取基础域名
        String baseHost;
        try {
            java.net.URL u = new java.net.URL(baseUrl);
            baseHost = u.getProtocol() + "://" + u.getHost();
        } catch (Exception e) {
            baseHost = "";
        }

        // 遍历所有a标签
        Elements anchors = doc.select("a[href]");
        for (Element a : anchors) {
            String href = a.attr("abs:href");// 绝对URL
            if (href.isEmpty()) continue;
            if (!baseHost.isEmpty() && !href.startsWith(baseHost)) continue;
            if (href.equals(baseUrl)) continue;// 排除列表页自身
            if (href.contains("#")) continue;// 排除锚点链接
            if (href.matches(".*\\.(css|js|jpg|png|gif|pdf|zip)$")) continue;
            if (!seen.contains(href)) {
                seen.add(href);
                links.add(href);
            }
        }
        return links;
    }

    private String matchDate(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher m = DATE_PATTERN.matcher(text);
        if (m.find()) {
            String year = m.group(1);
            String month = String.format("%02d", Integer.parseInt(m.group(2)));
            String day = String.format("%02d", Integer.parseInt(m.group(3)));
            return year + "-" + month + "-" + day;
        }
        return null;
    }

    private String cleanContent(String text) {
        if (text == null) return "";
        return text.replaceAll("[ \\t]+", " ")
                   .replaceAll("\\n{3,}", "\n\n")
                   .trim();
    }
}
