package com.party.regulation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.party.regulation.entity.RegulationChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RegulationChunkMapper extends BaseMapper<RegulationChunk> {

    @Select("SELECT * FROM regulation_chunk WHERE doc_id = #{docId} ORDER BY chunk_index ASC")
    List<RegulationChunk> selectByDocId(@Param("docId") Long docId);

    @Delete("DELETE FROM regulation_chunk WHERE doc_id = #{docId}")
    void deleteByDocId(@Param("docId") Long docId);

    @Select("SELECT COUNT(*) FROM regulation_chunk WHERE doc_id = #{docId}")
    int countByDocId(@Param("docId") Long docId);

    @Select("SELECT COUNT(*) FROM regulation_chunk")
    int countAll();
}
