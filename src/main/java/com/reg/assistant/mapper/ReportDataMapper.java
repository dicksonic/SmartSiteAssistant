package com.reg.assistant.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * M3-3 报告取数聚合查询（白名单规则的 SQL 唯一落脚点，仅 ReportDataProvider 实现调用）。
 * 国产库纪律：不用 DATE_FORMAT 等方言函数；时间过滤一律 created_at &gt;= from（含）AND &lt; toExclusive（不含，
 * 右开区间避免 23:59:59 边界丢单）；比率/除法不下 SQL，Java 侧算。&lt;script&gt; 动态条件先例见 KnowledgeMapper。
 */
@Mapper
public interface ReportDataMapper {

    /** 时间范围过滤片段（编译期常量，供各聚合 SQL 拼接；from/toExclusive 均可空，空则不限制该侧边界） */
    String TIME_ANDS =
            "<if test='from != null'> AND created_at &gt;= #{from} </if>" +
            "<if test='toExclusive != null'> AND created_at &lt; #{toExclusive} </if>";

    // ==================== 隐患域 hazard_records ====================

    @Select("<script>SELECT COUNT(*) FROM hazard_records <where>" + TIME_ANDS + "</where></script>")
    long countHazards(@Param("from") LocalDate from, @Param("toExclusive") LocalDate toExclusive);

    @Select("<script>SELECT category, COUNT(*) AS cnt FROM hazard_records <where>" + TIME_ANDS +
            "</where> GROUP BY category ORDER BY cnt DESC LIMIT #{maxRows}</script>")
    List<Map<String, Object>> groupHazardsByCategory(@Param("from") LocalDate from,
                                                     @Param("toExclusive") LocalDate toExclusive,
                                                     @Param("maxRows") int maxRows);

    @Select("<script>SELECT COUNT(*) FROM hazard_records <where> status='confirmed'" + TIME_ANDS + "</where></script>")
    long countConfirmedHazards(@Param("from") LocalDate from, @Param("toExclusive") LocalDate toExclusive);

    @Select("<script>SELECT status, COUNT(*) AS cnt FROM hazard_records <where>" + TIME_ANDS +
            "</where> GROUP BY status ORDER BY cnt DESC LIMIT #{maxRows}</script>")
    List<Map<String, Object>> groupHazardsByStatus(@Param("from") LocalDate from,
                                                   @Param("toExclusive") LocalDate toExclusive,
                                                   @Param("maxRows") int maxRows);

    // ==================== 平台运行域 call_logs ====================

    @Select("<script>SELECT COUNT(*) FROM call_logs <where>" + TIME_ANDS + "</where></script>")
    long countCallLogs(@Param("from") LocalDate from, @Param("toExclusive") LocalDate toExclusive);

    /** SUM 无数据返回 NULL，调用方（Provider）折 0 */
    @Select("<script>SELECT SUM(usage_amount) AS usage_total FROM call_logs <where>" + TIME_ANDS + "</where></script>")
    Double sumCallUsage(@Param("from") LocalDate from, @Param("toExclusive") LocalDate toExclusive);

    /** success 是 boolean 列（V1 基线，MySQL 5.7 存 tinyint）；按参数比较让驱动处理，不写方言字面量 */
    @Select("<script>SELECT COUNT(*) FROM call_logs <where> success = #{success}" + TIME_ANDS + "</where></script>")
    long countCallLogsBySuccess(@Param("success") boolean success,
                                @Param("from") LocalDate from, @Param("toExclusive") LocalDate toExclusive);

    @Select("<script>SELECT scenario, COUNT(*) AS cnt FROM call_logs <where>" + TIME_ANDS +
            "</where> GROUP BY scenario ORDER BY cnt DESC LIMIT #{maxRows}</script>")
    List<Map<String, Object>> groupCallsByScenario(@Param("from") LocalDate from,
                                                   @Param("toExclusive") LocalDate toExclusive,
                                                   @Param("maxRows") int maxRows);
}
