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
     * 只返回"活"的指标：draft（待确认）+ confirmed（已确认）+ locked（M3-3 已绑定取数）；deprecated 留痕行不返回（只查库可见）。
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
     * 调用方负责：code 生成与查重、origin 映射、status 恒为 draft、version 取模板当前版本、
     * ruleCode 白名单校验与 ruleVersion 权威取值（M3-3，均可空=未绑定）。
     *
     * @param row 指标行（templateId/metricCode/metricName/unit/origin/status/version/ruleCode/ruleVersion/specJson）
     * @return 影响行数（恒 1）；撞 uk(template_id, metric_code, version) 抛 DuplicateKeyException
     */
    @Insert("INSERT INTO report_metrics(template_id, metric_code, metric_name, unit, origin, status, version, rule_code, rule_version, spec_json) " +
            "VALUES(#{templateId}, #{metricCode}, #{metricName}, #{unit}, #{origin}, #{status}, #{version}, #{ruleCode}, #{ruleVersion}, #{specJson})")
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

    // ==================== M3-3 指标取数绑定 ====================

    /**
     * 取数候选指标：confirmed（待取数即锁）+ locked（已绑定可取数）。
     * 与 listMetricsByTemplate 的差别：不含 draft（未确认指标不参与取数）。
     *
     * @param templateId 模板 id
     * @return 指标行列表（含 ruleCode/ruleVersion），按 metricCode 升序
     */
    @Select("SELECT id, template_id AS templateId, metric_code AS metricCode, metric_name AS metricName, " +
            "unit, origin, status, version, formula, rule_code AS ruleCode, rule_version AS ruleVersion, " +
            "spec_json AS specJson, created_at AS createdAt, updated_at AS updatedAt " +
            "FROM report_metrics WHERE template_id=#{templateId} AND status IN ('confirmed','locked') ORDER BY metric_code")
    List<Map<String, Object>> listUsableMetricsByTemplate(@Param("templateId") long templateId);

    /**
     * 取数即锁：把本次成功取数的 confirmed 指标批量流转为 locked。
     * 守卫压 SQL 层（WHERE status='confirmed'）：已是 locked 的行不动，重复锁定幂等。
     * locked 行从此不被 saveMetrics 整体替换删改（防"重存指标把已取数定义冲掉"）。
     *
     * @param ids 本次取数成功的 confirmed 指标 id 集（调用方保证非空）
     * @return 流转条数
     */
    @Update("<script>UPDATE report_metrics SET status='locked', updated_at=CURRENT_TIMESTAMP " +
            "WHERE status='confirmed' AND id IN " +
            "<foreach collection='ids' item='mid' open='(' separator=',' close=')'>#{mid}</foreach></script>")
    int lockConfirmedMetricsByIds(@Param("ids") List<Long> ids);

    /**
     * 落一条取数结果块。blockId 取 metricCode，靠 uk(instance_id, block_id) 防同实例重复块。
     *
     * @param row 块行（instanceId/blockId/metricCode/columnsJson/rowsJson/metaJson/hash）
     * @return 影响行数（恒 1）
     */
    @Insert("INSERT INTO report_data_blocks(instance_id, block_id, metric_code, columns_json, rows_json, meta_json, hash) " +
            "VALUES(#{instanceId}, #{blockId}, #{metricCode}, #{columnsJson}, #{rowsJson}, #{metaJson}, #{hash})")
    int insertDataBlock(Map<String, Object> row);

    /**
     * failed 重试的清场半步：删掉该实例上次尝试留下的数据块（本次取数全部成功后才会再插入）。
     *
     * @param instanceId 实例 id
     * @return 删除行数
     */
    @Delete("DELETE FROM report_data_blocks WHERE instance_id=#{instanceId}")
    int deleteDataBlocksByInstance(@Param("instanceId") long instanceId);

    /**
     * 实例数据块列表（取数结果预览 / M3-4 长文生成与 M3-5 渲染的读入口）。
     *
     * @param instanceId 实例 id
     * @return 块行列表，按 blockId 升序（= metricCode 字典序）
     */
    @Select("SELECT id, instance_id AS instanceId, block_id AS blockId, metric_code AS metricCode, " +
            "columns_json AS columnsJson, rows_json AS rowsJson, meta_json AS metaJson, hash, created_at AS createdAt " +
            "FROM report_data_blocks WHERE instance_id=#{instanceId} ORDER BY block_id")
    List<Map<String, Object>> listDataBlocksByInstance(@Param("instanceId") long instanceId);

    /**
     * 写一条报告洞察（M3-3 只产 missing：指标未绑定取数规则，本期无数据；
     * summary/threshold/rank/trend 留 M3-4 长文生成）。
     *
     * @param row 洞察行（instanceId/insightType/text/refsJson/severity）
     * @return 影响行数（恒 1）
     */
    @Insert("INSERT INTO report_insights(instance_id, insight_type, text, refs_json, severity) " +
            "VALUES(#{instanceId}, #{insightType}, #{text}, #{refsJson}, #{severity})")
    int insertInsight(Map<String, Object> row);

    /**
     * failed 重试清场：只清 missing 型（M3-3 自产），不动将来 M3-4 生成的分析型洞察。
     *
     * @param instanceId 实例 id
     * @return 删除行数
     */
    @Delete("DELETE FROM report_insights WHERE instance_id=#{instanceId} AND insight_type='missing'")
    int deleteMissingInsightsByInstance(@Param("instanceId") long instanceId);

    // ==================== M3-4 长文生成（report_sections） ====================

    /**
     * 落一条报告章节。靠 uk(instance_id, section_code) 防同实例同节重复。
     *
     * @param row 章节行（instanceId/sectionCode/title/seq/content/refsJson）
     * @return 影响行数（恒 1）
     */
    @Insert("INSERT INTO report_sections(instance_id, section_code, title, seq, content, refs_json) " +
            "VALUES(#{instanceId}, #{sectionCode}, #{title}, #{seq}, #{content}, #{refsJson})")
    int insertSection(Map<String, Object> row);

    /**
     * 重新生成的清场半步：删掉该实例上次生成留下的全部章节（本次全部章节生成完毕后才会再插入）。
     *
     * @param instanceId 实例 id
     * @return 删除行数
     */
    @Delete("DELETE FROM report_sections WHERE instance_id=#{instanceId}")
    int deleteSectionsByInstance(@Param("instanceId") long instanceId);

    /**
     * 实例章节列表（前端预览 / M3-5 渲染导出的数据源）。
     *
     * @param instanceId 实例 id
     * @return 章节行列表，按 seq 升序（= 报告阅读顺序）
     */
    @Select("SELECT id, instance_id AS instanceId, section_code AS sectionCode, title, seq, content, " +
            "refs_json AS refsJson, created_at AS createdAt, updated_at AS updatedAt " +
            "FROM report_sections WHERE instance_id=#{instanceId} ORDER BY seq")
    List<Map<String, Object>> listSectionsByInstance(@Param("instanceId") long instanceId);

    /**
     * 重新生成清场：只清 summary 型（M3-4 自产），不动 M3-3 取数阶段产的 missing 型。
     *
     * @param instanceId 实例 id
     * @return 删除行数
     */
    @Delete("DELETE FROM report_insights WHERE instance_id=#{instanceId} AND insight_type='summary'")
    int deleteSummaryInsightsByInstance(@Param("instanceId") long instanceId);
}