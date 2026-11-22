package com.reg.assistant.report.provider;

import com.reg.assistant.common.ApiException;
import com.reg.assistant.mapper.ReportDataMapper;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地库取数实现（M3-3 首个生产实现）：按 ruleCode 分发到 ReportDataMapper 的预置聚合 SQL。
 * <p>
 * 契约纪律落实：
 * <ul>
 *   <li>ruleCode 必须命中白名单注册表（fail-closed，未知规则 400，SQL 无任何拼接入口）</li>
 *   <li>ruleVersion 钉定：指标绑定时的版本与注册表当前版本不一致 = 口径已漂移，
 *       拒绝按新口径重算旧绑定（409，提示重新绑定）——rule_version 列因此有真实语义</li>
 *   <li>orgScope/timeRange 强制透传进 DataBlock.meta：本地单租户实现虽不按 orgScope 过滤，
 *       但字段不得缺省（将来 HTTP 实现接多租户业务系统时走同一契约）</li>
 *   <li>hash = SHA-256(columns + rows 的 JSON 序列化)，给 D7 回放"依据可回溯"做内容指纹</li>
 * </ul>
 */
@Component
public class LocalDbReportDataProvider implements ReportDataProvider {

    private final ReportDataMapper dataMapper;
    private final ReportRuleRegistry ruleRegistry;
    private final ObjectMapper om = new ObjectMapper();

    public LocalDbReportDataProvider(ReportDataMapper dataMapper, ReportRuleRegistry ruleRegistry) {
        this.dataMapper = dataMapper;
        this.ruleRegistry = ruleRegistry;
    }

    @Override
    public DataBlock query(DataQuery q) {
        ReportRule rule = ruleRegistry.require(q.ruleCode());
        if (q.ruleVersion() != null && !q.ruleVersion().isBlank() && !rule.version().equals(q.ruleVersion())) {
            throw new ApiException(409, "指标 " + q.metricCode() + " 绑定的规则版本 " + q.ruleVersion()
                    + " 与当前版本 " + rule.version() + " 不一致，请重新绑定后重试");
        }
        long t0 = System.currentTimeMillis();
        LocalDate from = parseDate(q.params().get("timeFrom"), "timeFrom");
        LocalDate timeTo = parseDate(q.params().get("timeTo"), "timeTo");
        LocalDate toExclusive = timeTo == null ? null : timeTo.plusDays(1); // 右开区间，避免 23:59:59 边界丢单

        List<String> columns;
        List<List<Object>> rows;
        switch (rule.ruleCode()) {
            case "HAZARD_TOTAL" -> {
                columns = List.of("hazard_count");
                rows = List.of(List.<Object>of(dataMapper.countHazards(from, toExclusive)));
            }
            case "HAZARD_BY_CATEGORY" -> {
                columns = List.of("category", "hazard_count");
                rows = mapsToRows(dataMapper.groupHazardsByCategory(from, toExclusive, q.maxRows()), "category", "cnt");
            }
            case "HAZARD_CONFIRMED_TOTAL" -> {
                columns = List.of("confirmed_count");
                rows = List.of(List.<Object>of(dataMapper.countConfirmedHazards(from, toExclusive)));
            }
            case "HAZARD_CONFIRM_RATE" -> {
                long confirmed = dataMapper.countConfirmedHazards(from, toExclusive);
                long total = dataMapper.countHazards(from, toExclusive);
                columns = List.of("confirmed_count", "total_count", "confirm_rate");
                rows = List.of(List.<Object>of(confirmed, total, ratio(confirmed, total)));
            }
            case "HAZARD_BY_STATUS" -> {
                columns = List.of("status", "record_count");
                rows = mapsToRows(dataMapper.groupHazardsByStatus(from, toExclusive, q.maxRows()), "status", "cnt");
            }
            case "CALL_TOTAL" -> {
                columns = List.of("call_count");
                rows = List.of(List.<Object>of(dataMapper.countCallLogs(from, toExclusive)));
            }
            case "TOKEN_USAGE_TOTAL" -> {
                Double sum = dataMapper.sumCallUsage(from, toExclusive); // 无数据 SUM 返回 NULL，折 0
                columns = List.of("usage_total");
                rows = List.of(List.<Object>of(sum == null ? 0.0 : sum));
            }
            case "CALL_SUCCESS_RATE" -> {
                long success = dataMapper.countCallLogsBySuccess(true, from, toExclusive);
                long total = dataMapper.countCallLogs(from, toExclusive);
                columns = List.of("success_count", "total_count", "success_rate");
                rows = List.of(List.<Object>of(success, total, ratio(success, total)));
            }
            case "CALL_BY_SCENARIO" -> {
                columns = List.of("scenario", "call_count");
                rows = mapsToRows(dataMapper.groupCallsByScenario(from, toExclusive, q.maxRows()), "scenario", "cnt");
            }
            // 注册表有而 switch 漏 = 编程错误，fail-closed 不静默返回空块
            default -> throw new ApiException("规则已注册但未实现本地取数: " + rule.ruleCode());
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("provider", "local-db");
        meta.put("ruleCode", rule.ruleCode());
        meta.put("ruleVersion", rule.version());
        meta.put("ruleName", rule.name());
        meta.put("orgScope", q.orgScope());
        meta.put("timeRange", q.timeRange());
        meta.put("rowCount", rows.size());
        meta.put("elapsedMs", System.currentTimeMillis() - t0);
        return new DataBlock(q.metricCode(), q.metricCode(), columns, rows, meta, contentHash(columns, rows));
    }

    /** 比率计算下沉 Java（方言纪律：不写 ROUND(a/b) 之类 SQL）；分母 0 → 0.0，保留 4 位小数 */
    private static double ratio(long part, long total) {
        if (total == 0) return 0.0;
        return Math.round(part * 10000.0 / total) / 10000.0;
    }

    private static List<List<Object>> mapsToRows(List<Map<String, Object>> maps, String keyCol, String valueCol) {
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> m : maps) {
            rows.add(List.of(m.get(keyCol), m.get(valueCol)));
        }
        return rows;
    }

    /** 解析取数参数里的日期（yyyy-MM-dd）；null/空 → null（不限制该侧边界）；非法格式 400 */
    private LocalDate parseDate(Object v, String field) {
        String s = v == null ? "" : String.valueOf(v).trim();
        if (s.isBlank()) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            throw new ApiException("取数参数 " + field + " 日期格式应为 yyyy-MM-dd: " + s);
        }
    }

    /** 内容指纹：columns+rows 的 JSON 序列化做 SHA-256（D7 回放"依据可回溯"的挂钩；不含 metricCode，同数据同指纹） */
    private String contentHash(List<String> columns, List<List<Object>> rows) {
        try {
            String canonical = om.writeValueAsString(columns) + "\n" + om.writeValueAsString(rows);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new ApiException("数据块指纹计算失败: " + e.getMessage());
        }
    }
}
