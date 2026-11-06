package com.reg.assistant.mapper;

import com.reg.assistant.model.Entities.AssistantSession;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SessionMapper {

    @Insert("INSERT INTO assistant_sessions(token, platform_app_id, user_name, org_name, expires_at) " +
            "VALUES(#{token}, #{platformAppId}, #{userName}, #{orgName}, #{expiresAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AssistantSession session);

    @Select("SELECT s.*, p.platform_name FROM assistant_sessions s " +
            "JOIN platform_apps p ON p.id = s.platform_app_id WHERE s.token = #{token}")
    AssistantSession findByToken(String token);
}
