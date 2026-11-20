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

    // ==================== M3-2 候选确认 → report_metrics 正式表 ====================

    /**
     * 按模板查正式指标列表（确认界面/取数绑定的读入口）。
     * 只返回"活"的指标：draft（待确认）+ confirmed（已确认）；deprecated 留痕行不返回（只查库可见）。
     * 排序按 metric_code 字典序——编码统一 M%03d 零填充生成，字典序即数值序。
     *
     * @param templateId 模板 id
     * @return 指标行列表，按 metricCode 升序；无指标时返回空列表
     */
    @Select("SELECT id, template_id AS templateId, metric_code AS metricCode, metric_name AS metricName, " +
            "unit, origin, status, version, formula, rule_code AS ruleCode, rule_version AS ruleVersion, " +
            "spec_json AS specJson, created_at AS createdAt, updated_at AS updatedAt " +
            "FROM report_metrics WHERE template_id=#{templateId} AND status <> 'deprecated' ORDER BY metric_code")
    List<Map<String, Object>> listMetricsByTemplate(@Param("templateId") long templateId);

    /**
     * 按 id 查单条指标（含 deprecated，给废弃守卫前的存在性判断用）。
     *
     * @param id 指标 id
     * @return 指标行；不存在返回 null
     */
    @Select("SELECT id, template_id AS templateId, metric_code AS metricCode, metric_name AS metricName, " +
            "unit, origin, status, version, spec_json AS specJson, created_at AS createdAt, updated_at AS updatedAt " +
            "FROM report_metrics WHERE id=#{id}")
    Map<String, Object> findMetricById(@Param("id") long id);

    /**
     * 写入一条指标草稿（saveMetrics 整体替换时逐条调用）。
     * 调用方负责：code 生成与查重、origin 映射、status 恒为 draft、version 取模板当前版本。
     *
     * @param row 指标行（templateId/metricCode/metricName/unit/origin/status/version/specJson）
     * @return 影响行数（恒 1）；撞 uk(template_id, metric_code, version) 抛 DuplicateKeyException
     */
    @Insert("INSERT INTO report_metrics(template_id, metric_code, metric_name, unit, origin, status, version, spec_json) " +
            "VALUES(#{templateId}, #{metricCode}, #{metricName}, #{unit}, #{origin}, #{status}, #{version}, #{specJson})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertMetric(Map<String, Object> row);

    /**
     * 整体替换的"删旧"半步：只删可编辑行（draft/confirmed）。
     * deprecated 留痕行与 locked（M3-3 取数绑定后启用）行不动——
     * 前者保审计痕迹，后者防"重存指标把已绑数据的行冲掉"。
     *
     * @param templateId 模板 id
     * @return 删除行数
     */
    @Delete("DELETE FROM report_metrics WHERE template_id=#{templateId} AND status IN ('draft','confirmed')")
    int deleteEditableMetricsByTemplate(@Param("templateId") long templateId);

    /**
     * 查模板内现有最大指标编码（服务端生成 M%03d 时接续序号用）。
     * 故意查全状态（含 deprecated）：留痕行仍占着 uk，序号必须避开它们。
     *
     * @param templateId 模板 id
     * @return 最大 metric_code（字符串 max，M%03d 零填充下字典序=数值序）；无任何行返回 null
     */
    @Select("SELECT MAX(metric_code) FROM report_metrics WHERE template_id=#{templateId}")
    String maxMetricCode(@Param("templateId") long templateId);

    /**
     * 确认闸门第 1 步：把该模板的 draft 指标批量流转为 confirmed。
     *
     * @param templateId 模板 id
     * @return 流转条数；0 = 当前没有草稿可确认（上层据此提示"请先保存指标"）
     */
    @Update("UPDATE report_metrics SET status='confirmed', updated_at=CURRENT_TIMESTAMP WHERE template_id=#{templateId} AND status='draft'")
    int confirmDraftMetrics(@Param("templateId") long templateId);

    /**
     * 确认闸门第 2 步：模板 draft→confirmed。
     * 守卫压 SQL 层（WHERE status='draft'）：并发双击/重复流转返回 0，上层转业务异常，防并发双确认。
     *
     * @param id 模板 id
     * @return 影响行数；非 1 表示模板已不在 draft 状态
     */
    @Update("UPDATE report_templates SET status='confirmed', updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='draft'")
    int confirmTemplate(@Param("id") long id);

    /**
     * "改即回草稿"：已确认模板的指标被再次保存时，模板显式退回 draft，强制重新走确认闸门。
     * 模板本来就是 draft 时返回 0，无副作用——调用方无需关心当前状态，无脑调用即可。
     *
     * @param id 模板 id
     * @return 影响行数（1=发生了 confirmed→draft 回退；0=模板本来就是 draft）
     */
    @Update("UPDATE report_templates SET status='draft', updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='confirmed'")
    int reopenTemplateOnEdit(@Param("id") long id);

    /**
     * 废弃单条指标（软删留痕）：只允许 confirmed→deprecated。
     * 守卫压 SQL 层：draft 行请走整体替换删掉，deprecated/locked 行不可再操作——返回 0 上层转业务异常。
     *
     * @param id 指标 id
     * @return 影响行数；非 1 表示指标不存在或不在 confirmed 状态
     */
    @Update("UPDATE report_metrics SET status='deprecated', updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='confirmed'")
    int deprecateMetric(@Param("id") long id);
}