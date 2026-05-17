package com.party.regulation.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.time.LocalDate;
//文档表，一篇法规文档一行
@Data
@TableName("regulation_document")
public class RegulationDocument {
    @TableId(type = IdType.AUTO)
    private Long id;//	自增主键
    private String title;// 文章标题
    private String category;// 分类
    private String org;// 发布机构
    private LocalDate publishDate;// 发布日期
    /** 0待索引 1已索引 2索引失败 */
    private Integer status;
    private String filePath; //URL，原文链接，用于跳转
    private Integer charCount; //字符数
    private String content;//纯文本（检索用）
    /** 净化后的HTML正文，用于前端渲染 */
    private String htmlContent;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;// 入库时间
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;// 最后更新时间
}
