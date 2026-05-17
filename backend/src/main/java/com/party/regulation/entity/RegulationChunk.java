package com.party.regulation.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
//分块表，每个块一行
@Data
@TableName("regulation_chunk")
public class RegulationChunk {
    @TableId(type = IdType.AUTO)
    private Long id;//分块主键（块号）
    private Long docId;//所属文档ID（外键关联 regulation_document）
    private Integer chunkIndex;//	分块序号（第几个chunk）
    private String content;//	该分块的500字内容
    private Integer charCount;//该分块字符数
    private String chapter;//	所属章节标题（如"第一章 总则"）
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
