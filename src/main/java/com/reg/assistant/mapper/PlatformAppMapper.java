package com.reg.assistant.mapper;

import com.reg.assistant.model.Entities.PlatformApp;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlatformAppMapper {

    @Select("SELECT * FROM platform_apps WHERE app_key = #{appKey}")
    PlatformApp findByAppKey(String appKey);

    @Select("SELECT COUNT(*) FROM platform_apps")
    int count();
}
