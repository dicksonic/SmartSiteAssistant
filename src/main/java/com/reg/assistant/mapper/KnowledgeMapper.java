package com.reg.assistant.mapper;

import com.reg.assistant.model.Entities.KnowledgeItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeMapper {

    @Select("SELECT * FROM knowledge_items LIMIT #{limit}")
    List<KnowledgeItem> findAll(int limit);

    @Select("<script>SELECT * FROM knowledge_items " +
            "<where>" +
            "<foreach collection='keywords' item='k' separator=' OR '>" +
            "content LIKE CONCAT('%', #{k}, '%')" +
            "</foreach>" +
            "</where> LIMIT #{limit}</script>")
    List<KnowledgeItem> searchByKeywords(@Param("keywords") List<String> keywords, @Param("limit") int limit);

    @Select("SELECT * FROM knowledge_items WHERE title LIKE CONCAT('%', #{kw}, '%') OR content LIKE CONCAT('%', #{kw}, '%') LIMIT 20")
    List<KnowledgeItem> search(String kw);
}
