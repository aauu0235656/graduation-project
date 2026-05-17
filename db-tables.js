const { Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
        AlignmentType, BorderStyle, WidthType, ShadingType, PageBreak } = require("docx");
const fs = require("fs");

const border = { style: BorderStyle.SINGLE, size: 1, color: "999999" };
const cellBorders = {
  top: border, bottom: border,
  left: border, right: border,
};

// 灰色标题行背景
const headerShading = { fill: "D9D9D9", type: ShadingType.CLEAR };
// 白色数据行背景
const bodyShading   = { fill: "FFFFFF", type: ShadingType.CLEAR };

const TABLE_WIDTH = 9360;
const colWidths = [1400, 1300, 900, 1100, 2860, 1800];

function makeCell(text, shading, width) {
  return new TableCell({
    borders: cellBorders,
    width: { size: width, type: WidthType.DXA },
    shading: shading,
    verticalAlign: "center",
    margins: { top: 60, bottom: 60, left: 100, right: 100 },
    children: [new Paragraph({
      children: [new TextRun({ text, size: 21, font: "宋体" })], // 10.5pt
      alignment: AlignmentType.CENTER,
    })],
  });
}

function makeHeaderRow() {
  const headers = ["字段", "数据类型", "是否为空", "是否为主键", "注释", "约束说明"];
  return new TableRow({
    children: headers.map((h, i) => makeCell(h, headerShading, colWidths[i])),
    tableHeader: true,
  });
}

function makeRow(cells) {
  return new TableRow({
    children: cells.map((c, i) => makeCell(c, bodyShading, colWidths[i])),
  });
}

function makeTable(title, rows) {
  const tableRows = [makeHeaderRow(), ...rows.map(r => makeRow(r))];
  return [
    new Paragraph({
      spacing: { before: 240, after: 120 },
      alignment: AlignmentType.CENTER,
      children: [new TextRun({ text: title, size: 22, font: "宋体", bold: true })],
    }),
    new Table({
      width: { size: TABLE_WIDTH, type: WidthType.DXA },
      columnWidths: colWidths,
      rows: tableRows,
    }),
  ];
}

// ===================== 4张表数据 =====================
const tableData = [
  {
    title: "表 4-1 文档表 (regulation_document)",
    rows: [
      ["id", "bigint", "否", "是", "自增主键", "无"],
      ["title", "varchar(500)", "否", "否", "文章标题", "无"],
      ["category", "varchar(100)", "是", "否", "分类（如党章/纪律法规等）", "无"],
      ["org", "varchar(200)", "是", "否", "发布机构", "无"],
      ["publish_date", "date", "是", "否", "发布日期", "无"],
      ["status", "tinyint", "是", "否", "索引状态：0待索引/1已索引/2失败", "默认0"],
      ["file_path", "varchar(1000)", "是", "否", "原文链接URL，用于来源跳转", "无"],
      ["char_count", "int", "是", "否", "字符总数", "默认0"],
      ["content", "longtext", "是", "否", "纯文本正文，用于全文检索", "无"],
      ["html_content", "mediumtext", "是", "否", "净化后HTML正文，前端渲染用", "无"],
      ["create_time", "datetime", "是", "否", "入库时间", "默认当前时间"],
      ["update_time", "datetime", "是", "否", "最后更新时间", "默认当前时间"],
    ],
  },
  {
    title: "表 4-2 分块表 (regulation_chunk)",
    rows: [
      ["id", "bigint", "否", "是", "自增主键", "无"],
      ["doc_id", "bigint", "否", "否", "所属文档ID", "外键，级联删除"],
      ["chunk_index", "int", "否", "否", "分块序号（文档内从0开始）", "无"],
      ["content", "text", "否", "否", "该分块的文本内容", "无"],
      ["char_count", "int", "是", "否", "该分块字符数", "默认0"],
      ["chapter", "varchar(200)", "是", "否", "所属章节标题，用于答案引用", "无"],
      ["create_time", "datetime", "是", "否", "创建时间", "默认当前时间"],
    ],
  },
  {
    title: "表 4-3 问答历史表 (qa_history)",
    rows: [
      ["id", "bigint", "否", "是", "自增主键", "无"],
      ["question", "text", "否", "否", "用户问题", "无"],
      ["answer", "mediumtext", "是", "否", "系统生成的回答", "无"],
      ["sources", "varchar(2000)", "是", "否", "参考来源，JSON字符串", "无"],
      ["model_name", "varchar(100)", "是", "否", "调用的大模型名称", "无"],
      ["search_strategy", "varchar(20)", "是", "否", "检索策略：bm25/vector/hybrid", "无"],
      ["top_k", "int", "是", "否", "召回数量", "默认5"],
      ["create_time", "datetime", "是", "否", "创建时间", "默认当前时间"],
    ],
  },
  {
    title: "表 4-4 系统配置表 (system_config)",
    rows: [
      ["id", "bigint", "否", "是", "自增主键", "无"],
      ["config_key", "varchar(100)", "否", "否", "配置键（如api.key）", "唯一约束"],
      ["config_value", "text", "是", "否", "配置值", "无"],
      ["description", "varchar(500)", "是", "否", "配置项说明文字", "无"],
      ["update_time", "datetime", "是", "否", "更新时间", "默认当前时间"],
    ],
  },
];

// ===================== 组装文档 =====================
const doc = new Document({
  sections: [{
    properties: {
      page: { margin: { top: 1440, right: 1440, bottom: 1440, left: 1440 } },
    },
    children: tableData.flatMap((t, i) => {
      const blocks = makeTable(t.title, t.rows);
      if (i < tableData.length - 1) blocks.push(new Paragraph({ children: [new PageBreak()] }));
      return blocks;
    }),
  }],
});

Packer.toBuffer(doc).then(buffer => {
  fs.writeFileSync("d:/bishe/数据库表结构设计.docx", buffer);
  console.log("已生成: d:/bishe/数据库表结构设计.docx");
});
