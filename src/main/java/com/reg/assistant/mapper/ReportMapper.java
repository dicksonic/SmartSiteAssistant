package com.reg.assistant.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface ReportMapper {

    @Select("SELECT id, name, source_file_name AS sourceFileName, status, version, created_by AS createdBy, " +
            "parse_mode AS parseMode, block_count AS blockCount, parse_error AS parseError, " +
            "created_by AS createdBy,created_at AS createdAt, updated_at AS updatedAt FROM report_templates ORDER BY id DESC")
    List<Map<String, Object>> listTemplates();

    @Select("SELECT id, name, source_file_name AS sourceFileName, status, version, spec_json AS specJson, " +
            "created_by AS createdBy, created_at AS createdAt, updated_at AS updatedAt " +
            "FROM report_templates WHERE id=#{id}")
    Map<String, Object> findTemplateById(@Param("id") long id);

    @Insert("INSERT INTO report_instances(template_id, template_version, status, params_json, created_by) " +
            "VALUES(#{templateId}, #{templateVersion}, #{status}, #{paramsJson}, #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertInstance(Map<String, Object> row);

    @Select("SELECT id, template_id AS templateId, template_version AS templateVersion, status, params_json AS paramsJson, " +
            "error, pdf_path AS pdfPath, created_by AS createdBy, created_at AS createdAt, confirmed_at AS confirmedAt, exported_at AS exportedAt " +
            "FROM report_instances WHERE id=#{id}")
    Map<String, Object> findInstanceById(@Param("id") long id);

    /** 状态机守卫压 SQL：并发/重复流转返回 0，上层转业务异常 */
    @Update("UPDATE report_instances SET status=#{to}, error=NULL WHERE id=#{id} AND status=#{from}")
    int transitionInstance(@Param("id") long id, @Param("from") String from, @Param("to") String to);

    @Update("UPDATE report_instances SET status=#{to}, error=#{error} WHERE id=#{id} AND status=#{from}")
    int transitionInstanceWithError(@Param("id") long id, @Param("from") String from,
                                    @Param("to") String to, @Param("error") String error);

    @Insert("INSERT INTO report_templates(name, source_file_name, status, version, spec_json, created_by, storage_path, parse_mode, block_count, parse_error) " +
            "VALUES(#{name}, #{sourceFileName}, #{status}, #{version}, #{specJson}, #{createdBy}, #{storagePath}, #{parseMode}, #{blockCount}, #{parseError})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertTemplate(Map<String, Object> row);

    @Select("SELECT id, name, spec_json AS specJson, parse_mode AS parseMode, block_count AS blockCount, parse_error AS parseError " +
            "FROM report_templates WHERE id=#{id}")
    Map<String, Object> findTemplateParseById(@Param("id") long id);

    @Update("UPDATE report_templates SET spec_json=#{specJson}, block_count=#{blockCount}, updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    int updateTemplateSpec(@Param("id") long id, @Param("specJson") String specJson, @Param("blockCount") int blockCount);
}