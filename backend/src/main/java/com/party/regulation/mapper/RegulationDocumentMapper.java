package com.party.regulation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.party.regulation.entity.RegulationDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RegulationDocumentMapper extends BaseMapper<RegulationDocument> {

    @Select("SELECT COUNT(*) FROM regulation_document WHERE status = #{status}")
    int countByStatus(@Param("status") int status);//统计"待索引"/"已索引"的文档数量，给前端统计面板用

    @Select("SELECT COUNT(*) FROM regulation_document")
    int countAll();//总文档数
}
