package com.reg.assistant.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface CallLogMapper {

    @Insert("INSERT INTO call_logs(session_id, scenario, api_type, provider, model, usage_amount, unit, latency_ms, success) " +
            "VALUES(#{sessionId}, #{scenario}, #{apiType}, #{provider}, #{model}, #{usageAmount}, #{unit}, #{latencyMs}, #{success})")
    int insert(@Param("sessionId") Long sessionId,
               @Param("scenario") String scenario,
               @Param("apiType") String apiType,
               @Param("provider") String provider,
               @Param("model") String model,
               @Param("usageAmount") double usageAmount,
               @Param("unit") String unit,
               @Param("latencyMs") long latencyMs,
               @Param("success") boolean success);

    @Select("SELECT api_type AS apiType, scenario, COUNT(*) AS callCount, " +
            "SUM(usage_amount) AS totalUsage, unit, AVG(latency_ms) AS avgLatencyMs, AVG(success) AS successRate " +
            "FROM call_logs GROUP BY api_type, scenario, unit")
    List<Map<String, Object>> usageSummary();

    /*@Select("SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS day, COUNT(*) AS callCount, SUM(usage_amount) AS totalUsage " +
            "FROM call_logs GROUP BY DATE_FORMAT(created_at, '%Y-%m-%d') ORDER BY day")
    List<Map<String, Object>> usageDaily();*/

    /**
     * 按天统计的原始数据：日期分组放 Java 侧做。
     * 原因：DATE_FORMAT 是 MySQL 方言（达梦/金仓用 to_char，格式串语义还有差），SQL 里一处方言都不能留。
     * 失效条件：call_logs 量产到十万级后，改按时间范围过滤查询或建日汇总表。
     */
    @Select("SELECT created_at AS createdAt, usage_amount AS usageAmount FROM call_logs")
    List<Map<String, Object>> findDailySource();
}
