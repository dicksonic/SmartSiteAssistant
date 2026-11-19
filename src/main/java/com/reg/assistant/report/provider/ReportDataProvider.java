package com.reg.assistant.report.provider;

import java.util.List;
import java.util.Map;

/**
 * 报告数据供给契约。M3-0 只定边界；M3-3 给本地视图/mock/HTTP 实现。
 * 关键纪律：query 必须带 orgScope/timeRange/ruleVersion，任何实现不得绕开数据范围。
 */
public interface ReportDataProvider {

    record DataQuery(String metricCode,
                     String ruleCode,
                     String ruleVersion,
                     Map<String, Object> params,
                     String orgScope,
                     String timeRange,
                     int maxRows,
                     long timeoutMs) {
    }

    record DataBlock(String blockId,
                     String metricCode,
                     List<String> columns,
                     List<List<Object>> rows,
                     Map<String, Object> meta,
                     String hash) {
    }

    DataBlock query(DataQuery query);
}