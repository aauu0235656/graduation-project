package com.party.regulation.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
//问答历史表，用户每问一个问题就一行
@Data
@TableName("qa_history")//问答历史实体
public class QaHistory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String question;//
    private String answer;//
    private String sources;//参考来源（JSON字符串，含docId/title/chapter）
    private String modelName;//
    private String searchStrategy;//检索策略（bm25/vector/hybrid）
    private Integer topK;//召回数量
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
