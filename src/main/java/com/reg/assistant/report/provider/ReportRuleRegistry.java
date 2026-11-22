package com.reg.assistant.report.provider;

import com.reg.assistant.common.ApiException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 取数规则白名单注册表（M3-3）。
 * <p>
 * 规则是代码不是数据：新增规则 = 这里登记 + ReportDataMapper 加预置 SQL + LocalDbReportDataProvider 加分发。
 * 有意不做"库表可配置规则"——SQL 进库表意味着注入面与方言漂移面同时打开，政务场景不可接受。
 * 首批 9 条覆盖两域：隐患域（hazard_records 聚合）+ 平台运行域（call_logs 聚合）。
 */
@Component
public class ReportRuleRegistry {

    /** 当前规则版本：任何一条规则口径变更即整体升版，旧绑定在取数时被版本钉定拦下要求重新绑定 */
    public static final String RULE_VERSION = "1.0";

    private final Map<String, ReportRule> rules;

    public ReportRuleRegistry() {
        Map<String, ReportRule> m = new LinkedHashMap<>();
        register(m, new ReportRule("HAZARD_TOTAL", RULE_VERSION, "hazard", "隐患总数",
                "统计时间范围内隐患记录总条数。输出列: hazard_count", "条"));
        register(m, new ReportRule("HAZARD_BY_CATEGORY", RULE_VERSION, "hazard", "隐患分类分布",
                "按 category 分组统计条数，按条数降序。输出列: category, hazard_count", "条"));
        register(m, new ReportRule("HAZARD_CONFIRMED_TOTAL", RULE_VERSION, "hazard", "已确认隐患数",
                "统计时间范围内 status=confirmed 的隐患条数。输出列: confirmed_count", "条"));
        register(m, new ReportRule("HAZARD_CONFIRM_RATE", RULE_VERSION, "hazard", "隐患确认率",
                "已确认数/总数，除法在 Java 侧计算（方言纪律）。输出列: confirmed_count, total_count, confirm_rate", "%"));
        register(m, new ReportRule("HAZARD_BY_STATUS", RULE_VERSION, "hazard", "隐患状态分布",
                "按 status 分组统计条数。输出列: status, record_count", "条"));
        register(m, new ReportRule("CALL_TOTAL", RULE_VERSION, "platform", "模型调用总量",
                "统计时间范围内 call_logs 总条数。输出列: call_count", "次"));
        register(m, new ReportRule("TOKEN_USAGE_TOTAL", RULE_VERSION, "platform", "模型消耗总量",
                "SUM(usage_amount)，单位千 tokens；无数据时返回 0。输出列: usage_total", "千tokens"));
        register(m, new ReportRule("CALL_SUCCESS_RATE", RULE_VERSION, "platform", "调用成功率",
                "成功数/总数，除法在 Java 侧计算。输出列: success_count, total_count, success_rate", "%"));
        register(m, new ReportRule("CALL_BY_SCENARIO", RULE_VERSION, "platform", "分场景调用量",
                "按 scenario 分组统计条数，按条数降序。输出列: scenario, call_count", "次"));
        this.rules = Collections.unmodifiableMap(m);
    }

    private static void register(Map<String, ReportRule> m, ReportRule rule) {
        m.put(rule.ruleCode(), rule);
    }

    public Optional<ReportRule> find(String ruleCode) {
        return Optional.ofNullable(rules.get(ruleCode));
    }

    /** fail-closed：未知规则编码直接 400（绑定校验与取数共用的唯一校验口） */
    public ReportRule require(String ruleCode) {
        return find(ruleCode).orElseThrow(() ->
                new ApiException("未知取数规则: " + ruleCode + "，可用规则见 GET /api/report/rules"));
    }

    public List<ReportRule> list() {
        return new ArrayList<>(rules.values());
    }
}
