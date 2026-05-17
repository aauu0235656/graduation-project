package com.party.regulation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.party.regulation.entity.SystemConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SystemConfigMapper extends BaseMapper<SystemConfig> {

    @Select("SELECT config_value FROM system_config WHERE config_key = #{key}")
    String getValueByKey(String key);
}
