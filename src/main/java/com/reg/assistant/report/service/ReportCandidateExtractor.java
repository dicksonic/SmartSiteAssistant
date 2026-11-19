package com.reg.assistant.report.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M3-1b 候选指标/图表抽取器 v1：规则先行，LLM 后增强。
 * 输入 spec.blocks；输出 candidates，只进 report_templates.spec_json，不写 report_metrics 正式表。
 */
@Service
public class ReportCandidateExtractor {

    private static final Pattern CN_SECTION = Pattern.compile("^（[一二三四五六七八九十]+）(.+)$");
    private static final Pattern NUM = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");

    private static final Set<String> NON_METRIC_HEADERS = Set.of("排序", "序号", "区市", "区县", "区域名称", "所属辖区", "项目名称");

    private static final List<Seed> SEEDS = List.of(
            new Seed("GATHER_TOTAL", "应实现智慧工地资源汇聚项目", "个", "全市", "总体情况", "table",
                    List.of("应实现智慧工地资源汇聚项目", "应实现智慧工地", "资源汇聚项目")),
            new Seed("GATHER_DONE", "已完成智慧工地资源汇聚项目", "个", "全市", "总体情况", "table",
                    List.of("已完成智慧工地资源汇聚项目", "已完成智慧工地", "已汇聚项目")),
            new Seed("GATHER_RATE", "汇聚率", "%", "区市", "总体情况", "bar",
                    List.of("汇聚率")),
            new Seed("VIDEO_ACCESS_RATE", "视频项目接入率", "%", "全市", "安全文明建设", "bar",
                    List.of("全市累计接入视频项目", "接入视频项目")),
            new Seed("DUST_ACCESS_RATE", "扬尘项目接入率", "%", "全市", "安全文明建设", "bar",
                    List.of("全市累计接入扬尘项目", "接入扬尘项目")),
            new Seed("PM25_AVG", "PM2.5平均浓度", "ug/m³", "区市", "安全文明建设", "line",
                    List.of("PM2.5", "PM 2.5")),
            new Seed("PM10_AVG", "PM10平均浓度", "ug/m³", "区市", "安全文明建设", "line",
                    List.of("PM10")),
            new Seed("VIDEO_ONLINE_RATE", "视频监控在线率", "%", "项目", "安全文明建设", "bar",
                    List.of("视频监控在线率", "视频在线率")),
            new Seed("DUST_ONLINE_RATE", "扬尘监测设备在线率", "%", "项目", "安全文明建设", "bar",
                    List.of("扬尘监测设备在线率", "扬尘在线率")),
            new Seed("CRANE_RECORD", "起重机械设备备案数", "台", "全市", "特种设备情况", "table",
                    List.of("已备案起重机械设备", "起重机械设备")),
            new Seed("MANAGER_ATTEND", "项目经理平均到岗率", "%", "区市", "现场人员情况", "bar",
                    List.of("项目经理平均到岗率", "项目经理")),
            new Seed("DIRECTOR_ATTEND", "总监理工程师平均到岗率", "%", "区市", "现场人员情况", "bar",
                    List.of("总监理工程师平均到岗率", "总监理工程师")),
            new Seed("REALNAME_ATTEND_RATE", "实名制考勤项目占比", "%", "区市", "现场人员情况", "bar",
                    List.of("实名制考勤项目占", "考勤率", "实名制项目更新率")),
            new Seed("WAGE_PAID_PROJECTS", "发放工资项目数", "个", "区市", "农民工工资发放情况", "bar",
                    List.of("发放工资项目数", "发放工资项目", "已开通农民工工资专项账户"))
    );

    private record Seed(String code, String name, String unit, String dimension, String sectionHint,
                        String chart, List<String> keywords) {
    }

    public List<Map<String, Object>> extract(List<Map<String, Object>> blocks) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (blocks == null) return out;

        String section = "未分章";
        String subsection = "";
        for (int i = 0; i < blocks.size(); i++) {
            Map<String, Object> b = blocks.get(i);
            String type = str(b.get("type"));
            String text = str(b.get("text"));

            if ("paragraph".equals(type) && !text.isBlank()) {
                String secHit = hitSection(text);
                if (secHit != null) {
                    section = secHit;
                    subsection = "";
                } else {
                    Matcher m = CN_SECTION.matcher(text);
                    if (m.find()) subsection = m.group(1).trim();
                }
                extractParagraphSeeds(out, i, text, section, subsection);
            } else if ("table".equals(type)) {
                extractTable(out, i, b, section, subsection);
            }
        }
        return dedup(out);
    }

    private void extractParagraphSeeds(List<Map<String, Object>> out, int idx, String text,
                                       String section, String subsection) {
        for (Seed s : SEEDS) {
            if (s.sectionHint() != null && !section.contains(s.sectionHint())) continue;
            boolean hit = false;
            for (String kw : s.keywords()) {
                if (text.contains(kw)) { hit = true; break; }
            }
            if (!hit) continue;

            Map<String, Object> c = base(s.name(), s.unit(), s.dimension(), section, subsection, idx, s.chart());
            c.put("source", "paragraph");
            c.put("confidence", 0.70);
            c.put("matchedText", abbrev(text));
            Matcher n = NUM.matcher(text);
            if (n.find()) c.put("sampleValue", n.group(1));
            out.add(c);
        }
    }

    /**
     * 抽取表
     * @param out
     * @param idx
     * @param b
     * @param section
     * @param subsection
     */
    @SuppressWarnings("unchecked")
    private void extractTable(List<Map<String, Object>> out, int idx, Map<String, Object> b,
                              String section, String subsection) {
        Object rowsObj = b.get("rows");
        if (!(rowsObj instanceof List)) return;
        List<List<String>> rows = (List<List<String>>) rowsObj;
        if (rows.isEmpty()) return;

        int headerIdx = findHeaderRow(rows);
        if (headerIdx < 0) return;
        List<String> header = rows.get(headerIdx);

        int dimIdx = -1;
        String dimName = null;
        for (int c = 0; c < header.size(); c++) {
            String h = header.get(c);
            if (h == null) continue;
            if (h.contains("项目名称")) { dimIdx = c; dimName = "项目名称"; break; }
        }
        if (dimIdx < 0) {
            for (int c = 0; c < header.size(); c++) {
                String h = header.get(c);
                if (h == null) continue;
                if (h.contains("区市") || h.contains("区县") || h.contains("区域名称")) {
                    dimIdx = c; dimName = "区市"; break;
                }
            }
        }

        if (dimIdx < 0) return;


        for (int c = 0; c < header.size(); c++) {
            if (c == dimIdx) continue;
            String h = header.get(c);
            // 生成指标列时：
            String hn = h == null ? "" : h.replaceAll("\\s+", "");
            if (hn.isBlank() || NON_METRIC_HEADERS.contains(hn)) continue;

            String unit = inferUnit(hn);
            String name = hn.replaceAll("[（(].*?[）)]", "").trim();
            if (name.isBlank()) continue;

            Map<String, Object> cand = base(name, unit, dimName, section, subsection, idx,
                    hn.contains("率") || hn.contains("占比") ? "bar" : "table");
            cand.put("source", "table");
            cand.put("confidence", 0.78);
            cand.put("columnIndex", c);
            cand.put("matchedText", "表头: " + String.join(" | ", header));
            out.add(cand);
        }
    }

    private int findHeaderRow(List<List<String>> rows) {
        for (int i = 0; i < Math.min(rows.size(), 3); i++) {
            List<String> r = rows.get(i);
            String joined = String.join("|", r);
            if (joined.contains("区市") || joined.contains("区县") || joined.contains("区域") || joined.contains("项目名称")) {
                return i;
            }
        }
        return -1;
    }

    private Map<String, Object> base(String name, String unit, String dimension,
                                     String section, String subsection, int blockIndex, String chart) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("metricName", name);
        c.put("unit", unit);
        c.put("dimension", dimension);
        c.put("section", section);
        c.put("subsection", subsection);
        c.put("sourceBlockIndex", blockIndex);
        c.put("suggestedChart", chart);
        return c;
    }

    private List<Map<String, Object>> dedup(List<Map<String, Object>> in) {
        Map<String, Map<String, Object>> best = new LinkedHashMap<>();
        for (Map<String, Object> c : in) {
            String key = c.get("section") + "|" + c.get("metricName") + "|" + c.get("unit") + "|" + c.get("dimension");
            Map<String, Object> old = best.get(key);
            if (old == null || ((Number) c.get("confidence")).doubleValue() > ((Number) old.get("confidence")).doubleValue()) {
                best.put(key, c);
            }
        }
        return new ArrayList<>(best.values());
    }

    private String hitSection(String text) {
        Set<String> known = new LinkedHashSet<>(List.of("总体情况", "安全文明建设", "特种设备情况", "现场人员情况", "农民工工资发放情况"));
        for (String k : known) if (text.contains(k)) return k;
        return null;
    }


    private String inferUnit(String header) {
        if (header.contains("%") || header.contains("率") || header.contains("占比")) return "%";
        if (header.contains("万㎡")) return "万㎡";
        if (header.contains("ug/m³") || header.contains("浓度")) return "ug/m³";
        if (header.contains("预警") || header.contains("次数")) return "次";
        if (header.contains("备案证") || header.contains("登记证") || header.contains("证书") || header.contains("发证")) return "本";
        if (header.contains("台")) return "台";
        if (header.contains("人")) return "人";
        if (header.contains("元")) return "元";
        return "个";
    }

    private String abbrev(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}