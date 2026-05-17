const { Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
        AlignmentType, WidthType, BorderStyle, HeadingLevel } = require('docx');
const fs = require('fs');

const border = { style: BorderStyle.SINGLE, size: 1, color: "999999" };
const borders = { top: border, bottom: border, left: border, right: border };

function desc(text) {
  return new Paragraph({
    spacing: { before: 160, after: 60 },
    children: [new TextRun({ text, size: 22, font: "宋体", color: "444444" })]
  });
}

function createTable(title, rows) {
  const tableRows = [
    new TableRow({
      children: [
        new TableCell({
          borders,
          width: { size: 2200, type: WidthType.DXA },
          shading: { fill: "E8E8E8" },
          children: [new Paragraph({ children: [new TextRun({ text: "字段", bold: true, size: 22 })] })]
        }),
        new TableCell({
          borders,
          width: { size: 7200, type: WidthType.DXA },
          shading: { fill: "E8E8E8" },
          children: [new Paragraph({ children: [new TextRun({ text: "内容", bold: true, size: 22 })] })]
        })
      ]
    })
  ];
  
  rows.forEach(row => {
    tableRows.push(new TableRow({
      children: [
        new TableCell({
          borders,
          width: { size: 2200, type: WidthType.DXA },
          children: [new Paragraph({ children: [new TextRun({ text: row[0], size: 22 })] })]
        }),
        new TableCell({
          borders,
          width: { size: 7200, type: WidthType.DXA },
          children: [new Paragraph({ children: [new TextRun({ text: row[1], size: 22 })] })]
        })
      ]
    }));
  });

  return [
    new Paragraph({
      alignment: AlignmentType.CENTER,
      spacing: { before: 200, after: 100 },
      children: [new TextRun({ text: title, bold: true, size: 24 })]
    }),
    new Table({
      width: { size: 9400, type: WidthType.DXA },
      columnWidths: [2200, 7200],
      rows: tableRows
    })
  ];
}

const doc = new Document({
  styles: {
    default: { document: { run: { font: "宋体", size: 24 } } },
    paragraphStyles: [
      { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 32, bold: true, font: "黑体" },
        paragraph: { spacing: { before: 300, after: 200 } } },
      { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 28, bold: true, font: "黑体" },
        paragraph: { spacing: { before: 240, after: 160 } } }
    ]
  },
  sections: [{
    properties: {
      page: {
        size: { width: 11906, height: 16838 },
        margin: { top: 1440, right: 1440, bottom: 1440, left: 1440 }
      }
    },
    children: [
      new Paragraph({
        alignment: AlignmentType.CENTER,
        spacing: { after: 300 },
        children: [new TextRun({ text: "系统接口设计", bold: true, size: 36, font: "黑体" })]
      }),

      // ============ 4.5.1 智能问答 ============
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("4.5.1 智能问答")] }),
      
      desc("用于接收用户问题，执行检索增强生成，返回带来源的AI答案。"),
      ...createTable("表 4-1 RAG 智能问答接口", [
        ["请求方法", "POST"],
        ["请求地址", "/api/chat"],
        ["接口说明", "接收用户自然语言问题，执行双路检索+RRF融合+大模型生成，返回AI答案及参考来源"],
        ["请求参数", "question（字符串，用户问题）\nstrategy（字符串，检索策略：bm25/vector/hybrid，默认hybrid）\ntopK（整数，召回数量，默认5）\ntemperature（浮点数，生成温度，默认0.7）"],
        ["请求头字段", "Content-Type: application/json"],
        ["响应字段", "success（布尔值，是否成功）\nanswer（字符串，AI生成的答案）\nsources（数组，参考来源列表，含docId/title/chapter）\nsearchTime（字符串，检索耗时）\ngenerateTime（字符串，生成耗时）"]
      ]),

      desc("用于查询用户历史问答记录，支持分页限量返回。"),
      ...createTable("表 4-2 获取问答历史接口", [
        ["请求方法", "GET"],
        ["请求地址", "/api/chat/history"],
        ["接口说明", "获取最近的问答历史记录"],
        ["请求参数", "limit（整数，返回条数，默认20）"],
        ["请求头字段", "无"],
        ["响应字段", "success（布尔值）\nrecords（数组，问答历史列表，含question/answer/createTime）"]
      ]),

      // ============ 4.5.2 关键词检索 ============
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("4.5.2 关键词检索")] }),

      desc("基于Lucene BM25全文检索引擎，按关键词在知识库中检索相关法规文档。"),
      ...createTable("表 4-3 关键词全文检索接口", [
        ["请求方法", "GET"],
        ["请求地址", "/api/search"],
        ["接口说明", "基于Lucene BM25算法的关键词全文检索，支持分类过滤和高亮显示"],
        ["请求参数", "keyword（字符串，搜索关键词，必填）\ncategory（字符串，分类过滤，默认all）\ntopK（整数，返回条数，默认10）"],
        ["请求头字段", "无"],
        ["响应字段", "success（布尔值）\nkeyword（字符串，搜索关键词）\ntotal（整数，结果总数）\nresults（数组，结果列表，含docId/title/category/snippets/maxScore）"]
      ]),

      desc("获取当前Lucene索引库中的文档总数及索引健康状态信息。"),
      ...createTable("表 4-4 获取索引统计接口", [
        ["请求方法", "GET"],
        ["请求地址", "/api/index/stats"],
        ["接口说明", "获取Lucene索引的文档数量统计"],
        ["请求参数", "无"],
        ["请求头字段", "无"],
        ["响应字段", "success（布尔值）\nluceneDocCount（整数，Lucene索引中文档数量）"]
      ]),

      // ============ 4.5.3 文档录入与索引 ============
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("4.5.3 文档录入与索引")] }),

      desc("通过表单手动填写法规文档信息，将其录入知识库待索引队列。"),
      ...createTable("表 4-5 手动录入法规文档接口", [
        ["请求方法", "POST"],
        ["请求地址", "/api/document/add"],
        ["接口说明", "手动填写文档信息并录入数据库，状态设为待索引"],
        ["请求参数", "title（字符串，标题）\ncategory（字符串，分类）\norg（字符串，发布机构）\npublishDate（字符串，发布日期，格式yyyy-MM-dd）\ncontent（字符串，正文内容）"],
        ["请求头字段", "Content-Type: application/json"],
        ["响应字段", "success（布尔值）\nmessage（字符串，操作结果）\ndocId（长整数，新文档ID）"]
      ]),

      desc("上传本地文档文件，系统自动提取文本内容后存入知识库。"),
      ...createTable("表 4-6 文件导入文档接口", [
        ["请求方法", "POST"],
        ["请求地址", "/api/document/upload"],
        ["接口说明", "上传本地文件（PDF/DOCX/TXT/JSON）自动提取文本并入库"],
        ["请求参数", "file（文件，支持.pdf/.docx/.txt/.json）\ncategory（字符串，分类，默认其他）"],
        ["请求头字段", "Content-Type: multipart/form-data"],
        ["响应字段", "success（布尔值）\nmessage（字符串，操作结果）\ndocId（长整数，新文档ID）"]
      ]),

      desc("对指定文档执行分块、Lucene全文索引、向量嵌入三步索引构建流程。"),
      ...createTable("表 4-7 单文档构建索引接口", [
        ["请求方法", "POST"],
        ["请求地址", "/api/document/index/{docId}"],
        ["接口说明", "为指定文档构建完整索引：文本分块→Lucene全文索引→向量索引"],
        ["请求参数", "docId（路径参数，文档ID）"],
        ["请求头字段", "无"],
        ["响应字段", "success（布尔值）\nmessage（字符串，操作结果）\nchunkCount（整数，分块数量）\nsteps（数组，索引构建步骤说明）"]
      ]),

      desc("批量为待索引文档构建全文和向量双路索引，支持强制重建模式。"),
      ...createTable("表 4-8 批量构建索引接口", [
        ["请求方法", "POST"],
        ["请求地址", "/api/document/index/batch"],
        ["接口说明", "批量为待索引文档构建索引，支持强制重索引模式"],
        ["请求参数", "force（布尔值，是否强制重索引所有文档，默认false）"],
        ["请求头字段", "Content-Type: application/json"],
        ["响应字段", "success（布尔值）\ntotal（整数，处理文档总数）\nsuccessCount（整数，成功数）\nfailCount（整数，失败数）\ntotalChunks（整数，总分块数）\ntitles（数组，成功文档标题列表）\nmessage（字符串，操作结果）"]
      ]),

      // ============ 4.5.4 知识库管理 ============
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("4.5.4 知识库管理")] }),

      desc("分页查询知识库中的法规文档列表，支持关键词和分类多条件筛选。"),
      ...createTable("表 4-9 获取文档列表接口", [
        ["请求方法", "GET"],
        ["请求地址", "/api/documents"],
        ["接口说明", "分页获取文档列表，支持关键词搜索、分类筛选和状态筛选"],
        ["请求参数", "page（整数，页码，默认1）\nsize（整数，每页数量，默认10）\nkeyword（字符串，标题/内容关键词）\ncategory（字符串，分类过滤）\nstatus（整数，索引状态：0待索引/1已索引/2失败）"],
        ["请求头字段", "无"],
        ["响应字段", "success（布尔值）\ntotal（整数，总记录数）\npage（整数，当前页码）\nsize（整数，每页大小）\ndocuments（数组，文档列表，含id/title/category/org/charCount/status/chunkCount）"]
      ]),

      desc("根据文档ID获取单篇文档的完整内容及其所有分块详细信息。"),
      ...createTable("表 4-10 获取文档详情接口", [
        ["请求方法", "GET"],
        ["请求地址", "/api/document/{docId}"],
        ["接口说明", "获取单个文档的完整信息及所属分块列表"],
        ["请求参数", "docId（路径参数，文档ID）"],
        ["请求头字段", "无"],
        ["响应字段", "success（布尔值）\ndocument（对象，文档完整信息）\nchunks（数组，分块列表）\nchunkCount（整数，分块数量）"]
      ]),

      desc("级联删除指定文档及其分块、Lucene索引条目和向量嵌入数据。"),
      ...createTable("表 4-11 删除文档接口", [
        ["请求方法", "DELETE"],
        ["请求地址", "/api/document/{docId}"],
        ["接口说明", "删除文档及其关联的分块、Lucene索引和向量数据"],
        ["请求参数", "docId（路径参数，文档ID）"],
        ["请求头字段", "无"],
        ["响应字段", "success（布尔值）\nmessage（字符串，操作结果）"]
      ]),

      desc("汇总展示系统文档总量、索引状态、问答记录数等全局运行统计数据。"),
      ...createTable("表 4-12 获取系统统计接口", [
        ["请求方法", "GET"],
        ["请求地址", "/api/stats"],
        ["接口说明", "获取系统整体统计数据，包括文档数、分块数、索引状态等"],
        ["请求参数", "无"],
        ["请求头字段", "无"],
        ["响应字段", "success（布尔值）\ndocCount（整数，文档总数）\nchunkCount（整数，分块总数）\nindexedCount（整数，已索引数）\npendingCount（整数，待索引数）\nqaCount（整数，问答历史数）\nluceneDocCount（整数，Lucene索引数）\nvectorCount（整数，向量索引数）"]
      ]),

      desc("获取当前系统所有可配置项的键值列表，敏感信息自动脱敏显示。"),
      ...createTable("表 4-13 获取系统配置接口", [
        ["请求方法", "GET"],
        ["请求地址", "/api/config"],
        ["接口说明", "获取系统配置项列表，敏感信息自动脱敏显示"],
        ["请求参数", "无"],
        ["请求头字段", "无"],
        ["响应字段", "success（布尔值）\nconfig（对象，配置键值对，key为配置项名称）"]
      ]),

      desc("批量提交配置键值对，更新API密钥、模型参数等系统运行时配置。"),
      ...createTable("表 4-14 更新系统配置接口", [
        ["请求方法", "POST"],
        ["请求地址", "/api/config"],
        ["接口说明", "批量更新系统配置项"],
        ["请求参数", "JSON对象，键为配置项名称，值为新配置值"],
        ["请求头字段", "Content-Type: application/json"],
        ["响应字段", "success（布尔值）\nmessage（字符串，操作结果）"]
      ]),

      // ============ 4.5.5 爬虫采集 ============
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("4.5.5 爬虫采集")] }),

      desc("根据文章ID或URL抓取学习强国文章内容进行预览，不写入数据库。"),
      ...createTable("表 4-15 学习强国文章预览接口", [
        ["请求方法", "POST"],
        ["请求地址", "/api/crawler/xuexi/preview"],
        ["接口说明", "根据articleId或URL抓取学习强国文章内容并预览，不入库"],
        ["请求参数", "articleId（字符串，文章ID）或 url（字符串，文章链接）"],
        ["请求头字段", "Content-Type: application/json"],
        ["响应字段", "success（布尔值）\ndata（对象，文章信息，含title/content/source/url等）"]
      ]),

      desc("爬取学习强国单篇文章并将标题、正文、来源等信息存入知识库。"),
      ...createTable("表 4-16 学习强国单篇入库接口", [
        ["请求方法", "POST"],
        ["请求地址", "/api/crawler/xuexi/save"],
        ["接口说明", "抓取学习强国单篇文章并存入数据库，状态设为待索引"],
        ["请求参数", "articleId（字符串，文章ID）或 url（字符串，文章链接）\ncategory（字符串，分类，默认其他）"],
        ["请求头字段", "Content-Type: application/json"],
        ["响应字段", "success（布尔值）\nmessage（字符串，操作结果）\ndocId（长整数，新文档ID）\ntitle（字符串，文章标题）"]
      ]),

      desc("批量传入文章链接列表，并发爬取后批量存入知识库数据库。"),
      ...createTable("表 4-17 学习强国批量入库接口", [
        ["请求方法", "POST"],
        ["请求地址", "/api/crawler/xuexi/save-multi"],
        ["接口说明", "批量抓取学习强国文章并存入数据库"],
        ["请求参数", "urls（字符串数组，文章链接列表）\ncategory（字符串，分类，默认其他）"],
        ["请求头字段", "Content-Type: application/json"],
        ["响应字段", "success（布尔值）\ntotal（整数，URL总数）\nsuccessCount（整数，成功数）\nfailCount（整数，失败数）\ntitles（数组，成功文章标题列表）\nmessage（字符串，操作结果）"]
      ]),

      desc("爬取文章后调用大模型提取摘要、关键词及核心知识点结构化信息。"),
      ...createTable("表 4-18 学习强国AI分析接口", [
        ["请求方法", "POST"],
        ["请求地址", "/api/crawler/xuexi/analyze"],
        ["接口说明", "抓取文章并调用大模型生成摘要、关键词和关键知识点"],
        ["请求参数", "articleId（字符串，文章ID）或 url（字符串，文章链接）"],
        ["请求头字段", "Content-Type: application/json"],
        ["响应字段", "success（布尔值）\ntitle（字符串，文章标题）\nsource（字符串，文章来源）\npublishDate（字符串，发布日期）\nurl（字符串，原文链接）\nanalysis（对象，分析结果，含summary/keywords/keyPoints）"]
      ]),

      desc("批量爬取多篇文章并逐一调用大模型进行AI内容分析，汇总返回结果。"),
      ...createTable("表 4-19 学习强国批量AI分析接口", [
        ["请求方法", "POST"],
        ["请求地址", "/api/crawler/xuexi/analyze-multi"],
        ["接口说明", "批量抓取文章并调用大模型进行AI分析"],
        ["请求参数", "urls（字符串数组，文章链接列表）"],
        ["请求头字段", "Content-Type: application/json"],
        ["响应字段", "success（布尔值）\ntotal（整数，URL总数）\nsuccessCount（整数，成功数）\nfailCount（整数，失败数）\nitems（数组，分析结果列表）\nmessage（字符串，操作结果）"]
      ]),

      desc("爬取文章后调用大模型自动生成3至5组可用于训练的问答对数据。"),
      ...createTable("表 4-20 学习强国生成问答对接口", [
        ["请求方法", "POST"],
        ["请求地址", "/api/crawler/xuexi/generate-qa"],
        ["接口说明", "抓取文章并调用大模型自动生成3-5组问答对"],
        ["请求参数", "articleId（字符串，文章ID）或 url（字符串，文章链接）"],
        ["请求头字段", "Content-Type: application/json"],
        ["响应字段", "success（布尔值）\ntitle（字符串，文章标题）\nsource（字符串，文章来源）\npublishDate（字符串，发布日期）\nurl（字符串，原文链接）\nqaPairs（数组，问答对列表，每组含question/answer）"]
      ])
    ]
  }]
});

Packer.toBuffer(doc).then(buffer => {
  fs.writeFileSync("d:/bishe/系统接口设计.docx", buffer);
  console.log("文档已生成: d:/bishe/系统接口设计.docx");
});
