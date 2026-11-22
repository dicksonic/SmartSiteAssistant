package com.reg.assistant.report.service;

import com.reg.assistant.auth.TokenService;
import com.reg.assistant.common.ApiException;
import com.reg.assistant.mapper.ReportMapper;
import com.reg.assistant.model.Entities.AssistantSession;
import com.reg.assistant.report.provider.ReportDataProvider;
import com.reg.assistant.report.provider.ReportRule;
import com.reg.assistant.report.provider.ReportRuleRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReportService {

    private final TokenService tokenService;
    private final ReportMapper reportMapper;
    private final ReportStatusMachine statusMachine;
    private final ObjectMapper om = new ObjectMapper();

    private final ReportTemplateParser reportTemplateParser;
    private final String uploadDir;
    private final long maxSizeMb;

    private final ReportCandidateExtractor reportCandidateExtractor;

    // M3-3 指标取数绑定
    private final ReportRuleRegistry ruleRegistry;
    private final ReportDataProvider dataProvider;

    /** 单指标取数行数上限。取值依据：分类/场景维度真实基数 < 50，500 留 10 倍余量防异常维度爆炸拖垮内存；失效条件：出现高基数维度规则时上调或改分页 */
    private static final int DEFAULT_MAX_ROWS = 500;
    /** 取数超时契约值（毫秒）。本地 SQL 同步执行实际用不上，按契约字段透传；异步化（D8）落地时生效 */
    private static final long DEFAULT_QUERY_TIMEOUT_MS = 5000;

    public ReportService(TokenService tokenService, ReportMapper reportMapper, ReportStatusMachine statusMachine,ReportTemplateParser reportTemplateParser,@Value("${app.report.upload-dir:data/report-uploads}") String uploadDir,@Value("${app.report.max-size-mb:20}") long maxSizeMb,ReportCandidateExtractor reportCandidateExtractor,ReportRuleRegistry ruleRegistry,ReportDataProvider dataProvider) {
        this.tokenService = tokenService;
        this.reportMapper = reportMapper;
        this.statusMachine = statusMachine;
        this.reportTemplateParser = reportTemplateParser;
        this.uploadDir = uploadDir;
        this.maxSizeMb = maxSizeMb;
        this.reportCandidateExtractor = reportCandidateExtractor;
        this.ruleRegistry = ruleRegistry;
        this.dataProvider = dataProvider;
    }

    public List<Map<String, Object>> listTemplates(String token) {
        tokenService.requireSession(token);
        return reportMapper.listTemplates();
    }

    public Map<String, Object> getTemplate(String token, long id) {
        tokenService.requireSession(token);
        Map<String, Object> t = reportMapper.findTemplateById(id);
        if (t == null) throw new ApiException(404, "报告模板不存在: " + id);
        return t;
    }

    public Map<String, Object> createInstance(String token, Long templateId, Map<String, Object> params) {
        AssistantSession session = tokenService.requireSession(token);
        Integer templateVersion = null;
        if (templateId != null) {
            Map<String, Object> t = reportMapper.findTemplateById(templateId);
            if (t == null) throw new ApiException(404, "报告模板不存在: " + templateId);
            templateVersion = ((Number) t.get("version")).intValue();
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("templateId", templateId);
        row.put("templateVersion", templateVersion);
        row.put("status", ReportStatusMachine.DRAFT);
        row.put("paramsJson", writeJson(params == null ? Map.of() : params));
        row.put("createdBy", session.getUserName());
        reportMapper.insertInstance(row);
        return getInstance(token, ((Number) row.get("id")).longValue());
    }

    /**
     * 启动报告生成（M3-3 真取数版）：draft/failed → generating → preview。
     * <p>
     * 数据流：实例参数（params_json 的 orgScope/timeFrom/timeTo）+ 模板可用指标集（confirmed/locked）
     * → 逐指标按 ruleCode 经 ReportDataProvider 取数 → DataBlock 落 report_data_blocks
     * → 本次用到的 confirmed 指标"取数即锁"（locked，防后续整体替换冲掉已取数定义）
     * → 状态推 preview。未绑定规则的指标不算失败：跳过 + 落 missing insight + unbound 清单返回。
     * <p>
     * 幂等与失败语义：
     * <ul>
     *   <li>preview 状态重复 start 直接返回（既有幂等口径）</li>
     *   <li>先全部取数（只读）再统一落库：provider 异常时不会留下半截数据块</li>
     *   <li>failed 可重试：每次尝试落库前先清该实例旧数据块与旧 missing insight，不产生重复行</li>
     *   <li>取数/落库异常 → generating→failed + error 落列（截 500 字符），原异常上抛</li>
     *   <li>取数是同步的（本地 SQL 毫秒级）；异步生成任务列 D8</li>
     * </ul>
     *
     * @param token 免登 token
     * @param id    实例 id
     * @return 实例行 + blocks（数据块数）+ unboundMetrics（未绑定规则跳过的指标清单）
     */
    public Map<String, Object> start(String token, long id) {
        tokenService.requireSession(token);
        Map<String, Object> inst = requireInstance(id);
        String status = (String) inst.get("status");
        if (ReportStatusMachine.PREVIEW.equals(status)) {
            return inst; // 幂等：重复 start 不报错
        }
        statusMachine.assertTransition(status, ReportStatusMachine.GENERATING);

        Long templateId = inst.get("templateId") == null ? null : ((Number) inst.get("templateId")).longValue();
        if (templateId == null) throw new ApiException("报告实例未关联模板，无法取数: " + id);
        Map<String, Object> t = reportMapper.findTemplateById(templateId);
        if (t == null) throw new ApiException(404, "报告模板不存在: " + templateId);
        if (!"confirmed".equals(t.get("status"))) {
            throw new ApiException("模板未完成指标确认（当前状态 " + t.get("status") + "），请先走确认闸门");
        }
        List<Map<String, Object>> usable = reportMapper.listUsableMetricsByTemplate(templateId);
        if (usable.isEmpty()) throw new ApiException("模板无可用指标（confirmed/locked），无法取数");

        int n = reportMapper.transitionInstance(id, status, ReportStatusMachine.GENERATING);
        if (n != 1) {
            throw new ApiException("报告状态已变化，请刷新后重试");
        }

        List<String> unbound = new ArrayList<>();
        try {
            String paramsJson = (String) inst.get("paramsJson");
            Map<String, Object> params = (paramsJson == null || paramsJson.isBlank()) ? Map.of() : readSpec(paramsJson);
            String orgScope = str(params.get("orgScope"));
            if (orgScope.isBlank()) orgScope = "default";
            String timeRange = buildTimeRange(params);

            List<Map<String, Object>> blockRows = new ArrayList<>();
            List<Long> usedConfirmedMetricIds = new ArrayList<>();
            for (Map<String, Object> metric : usable) {
                String metricCode = (String) metric.get("metricCode");
                String ruleCode = (String) metric.get("ruleCode");
                if (ruleCode == null || ruleCode.isBlank()) {
                    unbound.add(metricCode + "(" + metric.get("metricName") + ")");
                    continue;
                }
                ReportDataProvider.DataQuery q = new ReportDataProvider.DataQuery(
                        metricCode, ruleCode, (String) metric.get("ruleVersion"),
                        params, orgScope, timeRange, DEFAULT_MAX_ROWS, DEFAULT_QUERY_TIMEOUT_MS);
                ReportDataProvider.DataBlock block = dataProvider.query(q);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("instanceId", id);
                row.put("blockId", block.blockId());
                row.put("metricCode", block.metricCode());
                row.put("columnsJson", writeJson(block.columns()));
                row.put("rowsJson", writeJson(block.rows()));
                row.put("metaJson", writeJson(block.meta()));
                row.put("hash", block.hash());
                blockRows.add(row);
                if ("confirmed".equals(metric.get("status"))) {
                    usedConfirmedMetricIds.add(((Number) metric.get("id")).longValue());
                }
            }

            // 取数全部成功才动库：清场（failed 重试幂等）→ 落块 → 落 missing → 取数即锁 → 推 preview
            reportMapper.deleteDataBlocksByInstance(id);
            reportMapper.deleteMissingInsightsByInstance(id);
            for (Map<String, Object> row : blockRows) {
                reportMapper.insertDataBlock(row);
            }
            for (String u : unbound) {
                Map<String, Object> insight = new LinkedHashMap<>();
                insight.put("instanceId", id);
                insight.put("insightType", "missing");
                insight.put("text", "指标未绑定取数规则，本期无数据: " + u);
                insight.put("refsJson", writeJson(Map.of("metric", u)));
                insight.put("severity", "warn");
                reportMapper.insertInsight(insight);
            }
            if (!usedConfirmedMetricIds.isEmpty()) {
                reportMapper.lockConfirmedMetricsByIds(usedConfirmedMetricIds);
            }
            int m = reportMapper.transitionInstance(id, ReportStatusMachine.GENERATING, ReportStatusMachine.PREVIEW);
            if (m != 1) throw new ApiException("报告状态已变化，请刷新后重试");
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            reportMapper.transitionInstanceWithError(id, ReportStatusMachine.GENERATING,
                    ReportStatusMachine.FAILED, msg.length() > 500 ? msg.substring(0, 500) : msg);
            throw e;
        }

        Map<String, Object> result = new LinkedHashMap<>(getInstance(token, id));
        result.put("blocks", reportMapper.listDataBlocksByInstance(id).size());
        result.put("unboundMetrics", unbound);
        return result;
    }

    /** 实例参数 → 契约 timeRange 字符串；timeFrom/timeTo 均缺省时 "all"（格式校验在 Provider 解析时做） */
    private String buildTimeRange(Map<String, Object> params) {
        String from = str(params.get("timeFrom"));
        String to = str(params.get("timeTo"));
        if (from.isBlank() && to.isBlank()) return "all";
        return (from.isBlank() ? "..." : from) + "~" + (to.isBlank() ? "..." : to);
    }

    public Map<String, Object> getInstance(String token, long id) {
        tokenService.requireSession(token);
        return requireInstance(id);
    }

    private Map<String, Object> requireInstance(long id) {
        Map<String, Object> inst = reportMapper.findInstanceById(id);
        if (inst == null) throw new ApiException(404, "报告实例不存在: " + id);
        return inst;
    }

    /** 取数规则目录（M3-3，指标绑定下拉的数据源）：公开全部白名单规则 */
    public List<ReportRule> listRules(String token) {
        tokenService.requireSession(token);
        return ruleRegistry.list();
    }

    /** 实例数据块列表（M3-3，取数结果预览 / M3-4 长文生成与 M3-5 渲染的读入口） */
    public Map<String, Object> listBlocks(String token, long instanceId) {
        tokenService.requireSession(token);
        requireInstance(instanceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", instanceId);
        result.put("blocks", reportMapper.listDataBlocksByInstance(instanceId));
        return result;
    }

    /** 统一 JSON 序列化出口（M3-3 起入参放宽到 Object：数据块的 columns/rows 是 List） */
    private String writeJson(Object v) {
        try {
            return om.writeValueAsString(v);
        } catch (Exception e) {
            throw new ApiException("报告参数序列化失败: " + e.getMessage());
        }
    }

    public Map<String, Object> uploadTemplate(String token, String originalFilename, long size, InputStream in) {
        AssistantSession session = tokenService.requireSession(token);
        if (size <= 0) throw new ApiException("文件为空");
        if (size > maxSizeMb * 1024 * 1024) throw new ApiException("文件超过大小限制: " + maxSizeMb + "MB");

        String cleanName = originalFilename == null ? "report.docx" : originalFilename.replaceAll("[\\\\/:*?\"<>|]", "_");
        String lower = cleanName.toLowerCase();
        String ext = lower.endsWith(".docx") ? "docx" : lower.endsWith(".pdf") ? "pdf" : null;
        if (ext == null) {
            throw new ApiException("仅支持 .docx/.pdf 报告模板");
        }

        try {
            Files.createDirectories(Path.of(uploadDir));
            String stored = uploadDir + "/" + System.currentTimeMillis() + "-" + cleanName;
            Files.copy(in, Path.of(stored), StandardCopyOption.REPLACE_EXISTING);

            ReportTemplateParser.ParseResult parsed;
            try (InputStream pin = Files.newInputStream(Path.of(stored))) {
                parsed = reportTemplateParser.parse(ext, pin);
            }



            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("rawText", parsed.rawText());
            spec.put("blocks", parsed.blocks());
            spec.put("stats", Map.of("chars", parsed.rawText().length(), "blocks", parsed.blocks().size()));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> blocks = (List<Map<String, Object>>) spec.get("blocks");
            List<Map<String, Object>> candidates = reportCandidateExtractor.extract(blocks);
            spec.put("candidates", candidates);
            spec.put("candidateStats", Map.of(
                    "count", candidates.size(),
                    "paragraph", candidates.stream().filter(c -> "paragraph".equals(c.get("source"))).count(),
                    "table", candidates.stream().filter(c -> "table".equals(c.get("source"))).count()
            ));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", cleanName.replaceFirst("\\.(docx|doc)$", ""));
            row.put("sourceFileName", cleanName);
            row.put("status", parsed.error() == null ? "draft" : "failed");
            row.put("version", 1);
            row.put("specJson", writeJson(spec));
            row.put("createdBy", session.getUserName());
            row.put("storagePath", stored);
            row.put("parseMode", parsed.parseMode());
            row.put("blockCount", parsed.blocks().size());
            row.put("parseError", parsed.error());
            reportMapper.insertTemplate(row);
            return getTemplate(token, ((Number) row.get("id")).longValue());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("报告模板上传失败: " + e.getMessage());
        }
    }

    /**
     * 可重复触发的抽取接口
     * @param token
     * @param id
     * @return
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> reExtractCandidates(String token, long id) {
        tokenService.requireSession(token);
        Map<String, Object> t = reportMapper.findTemplateParseById(id);
        if (t == null) throw new ApiException(404, "报告模板不存在: " + id);
        String specJson = (String) t.get("specJson");
        Map<String, Object> spec;
        try {
            spec = om.readValue(specJson, Map.class);
        } catch (Exception e) {
            throw new ApiException("模板 spec_json 解析失败: " + e.getMessage());
        }
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) spec.get("blocks");
        List<Map<String, Object>> candidates = reportCandidateExtractor.extract(blocks);
        spec.put("candidates", candidates);
        spec.put("candidateStats", Map.of("count", candidates.size()));
        reportMapper.updateTemplateSpec(id, writeJson(spec), blocks == null ? 0 : blocks.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("candidateCount", candidates.size());
        result.put("candidates", candidates);
        return result;
    }

    public Map<String, Object> getTemplateRaw(String token, long id) {
        tokenService.requireSession(token);
        Map<String, Object> t = reportMapper.findTemplateParseById(id);
        if (t == null) throw new ApiException(404, "报告模板不存在: " + id);
        return t;
    }

    // ==================== M3-2 候选确认 → report_metrics 正式表 ====================
    //
    // 数据流：spec_json.candidates（机器建议，可重复抽取）→ 人工编辑后的指标集整体落 report_metrics
    // 三条语义：
    //   ① 保存即草稿——saveMetrics 整体替换可编辑行（draft/confirmed），deprecated 留痕行与 locked 行不动；
    //      若模板已 confirmed，保存动作把模板显式退回 draft（"改即回草稿"），要求重新走确认闸门。
    //   ② 确认是幂等闸门——draft 指标批量→confirmed + 模板 draft→confirmed，守卫压 SQL；
    //      模板已 confirmed 时重复确认直接幂等返回，不报错（前端重复点击/重试安全）。
    //   ③ 废弃走 deprecate——confirmed→deprecated 软删留痕；locked 状态留给 M3-3 取数绑定后启用。

    /** 单模板指标数上限。取值依据：真实巡检报告全章节候选实测 53 条，500 留 10 倍余量防超大请求体打满表；失效条件：真实模板指标数逼近该值时上调 */
    private static final int MAX_METRICS_PER_TEMPLATE = 500;
    /** 图型值域：与 V3 report_charts 的 CHECK 同口径（MySQL 5.7 静默忽略 CHECK，此处代码层强制） */
    private static final Set<String> CHART_TYPES = Set.of("bar", "line", "pie", "table", "stackbar");
    /** 服务端自动编码格式：M + 三位序号，按模板内现有最大序号接续 */
    private static final Pattern METRIC_CODE_SEQ = Pattern.compile("^M(\\d{3,})$");

    /**
     * 拉取候选集（确认界面的数据源）。
     * 数据流：report_templates.spec_json（上传/重抽时写入）→ 读出 candidates 数组原样返回。
     * 只读不落库——候选是"机器建议"，只有人工保存后才进 report_metrics 成为正式指标。
     *
     * @param token      免登 token
     * @param templateId 模板 id
     * @return {id, candidates}；候选为空时返回空数组
     * @throws ApiException 404 模板不存在；400 spec_json 损坏
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getCandidates(String token, long templateId) {
        tokenService.requireSession(token);
        Map<String, Object> t = reportMapper.findTemplateParseById(templateId);
        if (t == null) throw new ApiException(404, "报告模板不存在: " + templateId);
        Map<String, Object> spec = readSpec((String) t.get("specJson"));
        Object candidates = spec.get("candidates");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", templateId);
        result.put("candidates", candidates == null ? List.of() : candidates);
        return result;
    }

    /**
     * 查正式指标列表（含 draft/confirmed，不含 deprecated 留痕行）。
     * 用途：确认界面回显"已保存的指标集"、M3-3 取数绑定的读入口。
     *
     * @param token      免登 token
     * @param templateId 模板 id
     * @return {id, templateStatus, metrics}
     * @throws ApiException 404 模板不存在
     */
    public Map<String, Object> listMetrics(String token, long templateId) {
        tokenService.requireSession(token);
        Map<String, Object> t = reportMapper.findTemplateById(templateId);
        if (t == null) throw new ApiException(404, "报告模板不存在: " + templateId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", templateId);
        result.put("templateStatus", t.get("status"));
        result.put("metrics", reportMapper.listMetricsByTemplate(templateId));
        return result;
    }

    /**
     * 保存指标集（整体替换语义，"保存即草稿"）。
     * <p>
     * 数据流：前端把人工编辑后的最终指标数组（从 candidates 勾选/改名/删行/手动新增得来）整体提交
     * → 逐行校验装配（{@link #buildMetricRow}）→ 同一事务内"删旧 + 逐条插新"
     * → 若模板此前已 confirmed，显式退回 draft（"改即回草稿"，强制重新走确认闸门）。
     * <p>
     * 关键口径：
     * <ul>
     *   <li>metricCode 缺省时服务端生成（M%03d，按模板内全状态最大序号接续，避开 deprecated 留痕行的 uk 占用）</li>
     *   <li>origin 由服务端按候选来源映射（paragraph/table→extracted，其余→custom），不信客户端传值</li>
     *   <li>ruleCode 可选：非空必须命中取数规则白名单（M3-3），ruleVersion 由注册表权威写入</li>
     *   <li>删旧只删 draft/confirmed 行：deprecated 留痕行与 locked 行（M3-3 取数即锁后启用）不动</li>
     *   <li>撞 uk(template_id, metric_code, version) → 409，事务回滚不留半截</li>
     * </ul>
     *
     * @param token      免登 token
     * @param templateId 模板 id（locked/deprecated 状态拒绝编辑）
     * @param metrics    人工编辑后的最终指标数组，每行字段见 buildMetricRow；非空、≤500 行
     * @return {id, saved, templateStatus:"draft"}
     * @throws ApiException 400 校验失败（空数组/超限/缺名称/非法图型/编码重复）；404 模板不存在；409 编码撞留痕行
     */
    @Transactional
    public Map<String, Object> saveMetrics(String token, long templateId, List<Map<String, Object>> metrics) {
        tokenService.requireSession(token);
        Map<String, Object> t = reportMapper.findTemplateById(templateId);
        if (t == null) throw new ApiException(404, "报告模板不存在: " + templateId);
        String tStatus = (String) t.get("status");
        if ("locked".equals(tStatus) || "deprecated".equals(tStatus)) {
            throw new ApiException("模板状态为 " + tStatus + "，不允许编辑指标");
        }
        if (metrics == null || metrics.isEmpty()) throw new ApiException("指标列表为空");
        if (metrics.size() > MAX_METRICS_PER_TEMPLATE) {
            throw new ApiException("指标数量超过上限: " + MAX_METRICS_PER_TEMPLATE);
        }

        int version = ((Number) t.get("version")).intValue();
        int[] nextSeq = {nextMetricSeq(reportMapper.maxMetricCode(templateId))};
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> m : metrics) {
            rows.add(buildMetricRow(templateId, version, m, seen, nextSeq));
        }

        reportMapper.deleteEditableMetricsByTemplate(templateId);
        try {
            for (Map<String, Object> row : rows) {
                reportMapper.insertMetric(row);
            }
        } catch (DuplicateKeyException e) {
            // uk(template_id, metric_code, version) 撞 deprecated/locked 留痕行：提示换码，事务回滚不留半截
            throw new ApiException(409, "指标编码与历史保留行冲突，请修改 metricCode 后重试");
        }
        reportMapper.reopenTemplateOnEdit(templateId); // confirmed→draft 显式回退；draft 时 0 行无副作用

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", templateId);
        result.put("saved", rows.size());
        result.put("templateStatus", "draft");
        return result;
    }

    /**
     * 确认闸门（幂等）：把模板从"草稿"推入"已确认"。
     * <p>
     * 数据流：draft 指标批量 → confirmed（第 1 步）→ 模板 draft → confirmed（第 2 步），
     * 两步同一事务，守卫都压 SQL 层（WHERE 带原状态），并发双击/重试不会产生半截状态。
     * <p>
     * 幂等口径：模板已是 confirmed 时直接返回 {confirmed:0} 不报错——前端重复点击、网络重试均安全。
     *
     * @param token      免登 token
     * @param templateId 模板 id
     * @return {id, confirmed, templateStatus}
     * @throws ApiException 404 模板不存在；400 模板非 draft/confirmed（locked 等）、或无草稿可确认；400 模板状态并发变化
     */
    @Transactional
    public Map<String, Object> confirmMetrics(String token, long templateId) {
        tokenService.requireSession(token);
        Map<String, Object> t = reportMapper.findTemplateById(templateId);
        if (t == null) throw new ApiException(404, "报告模板不存在: " + templateId);
        String tStatus = (String) t.get("status");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", templateId);
        if ("confirmed".equals(tStatus)) {
            result.put("confirmed", 0); // 幂等：重复确认不报错
            result.put("templateStatus", "confirmed");
            return result;
        }
        if (!"draft".equals(tStatus)) {
            throw new ApiException("模板状态为 " + tStatus + "，不允许确认");
        }
        int n = reportMapper.confirmDraftMetrics(templateId);
        if (n == 0) throw new ApiException("没有待确认的指标草稿，请先保存指标");
        int m = reportMapper.confirmTemplate(templateId);
        if (m != 1) throw new ApiException("模板状态已变化，请刷新后重试");
        result.put("confirmed", n);
        result.put("templateStatus", "confirmed");
        return result;
    }

    /**
     * 废弃单条已确认指标（软删留痕，行不物理删除——审计需要"当时确认了哪些指标"可回溯）。
     * 守卫压 SQL 层（WHERE status='confirmed'）：draft 行请走整体替换删除，locked/deprecated 行不可再操作。
     *
     * @param token    免登 token
     * @param metricId 指标 id
     * @return {id, status:"deprecated"}
     * @throws ApiException 400 指标不存在或不在 confirmed 状态
     */
    public Map<String, Object> deprecateMetric(String token, long metricId) {
        tokenService.requireSession(token);
        int n = reportMapper.deprecateMetric(metricId);
        if (n != 1) throw new ApiException("指标不存在或不在已确认状态，无法废弃: " + metricId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", metricId);
        result.put("status", "deprecated");
        return result;
    }

    /**
     * 单行指标的校验与装配（saveMetrics 的逐行处理）。
     * <p>
     * 按序做四件事：①编码——缺省生成 M%03d（nextSeq 接续），超长/请求体内重复即拒；
     * ②必填与长度——metricName 必填 ≤255，unit ≤32；③图型值域——缺省 table，须在 CHART_TYPES 内；
     * ④origin 映射与 spec_json 组装——表内无列承载的明细（维度/章节/来源上下文/置信度）整体进 spec_json，
     * 供 M3-3 取数绑定与前端回显。
     *
     * @param templateId 模板 id
     * @param version    指标版本（取模板当前 version）
     * @param m          入参行：metricCode/metricName/unit/ruleCode?/dimension/section/subsection/suggestedChart/source/confidence/matchedText/...
     * @param seen       请求体内编码查重集（调用方持有，逐行 add）
     * @param nextSeq    自动编码序号游标（单元素数组当可变引用用，调用方持有）
     * @return 可直接 insertMetric 的行（status 恒 draft）
     * @throws ApiException 400 各类校验失败
     */
    private Map<String, Object> buildMetricRow(long templateId, int version, Map<String, Object> m,
                                               Set<String> seen, int[] nextSeq) {
        String code = str(m.get("metricCode"));
        if (code.isBlank()) {
            code = String.format("M%03d", nextSeq[0]++);
        }
        if (code.length() > 128) throw new ApiException("metricCode 超长(>128): " + code);
        if (!seen.add(code)) throw new ApiException("指标编码重复: " + code);

        String name = str(m.get("metricName"));
        if (name.isBlank()) throw new ApiException("指标名称不能为空: " + code);
        if (name.length() > 255) throw new ApiException("指标名称超长(>255): " + code);

        String unit = str(m.get("unit"));
        if (unit.length() > 32) throw new ApiException("单位超长(>32): " + code);

        String chart = str(m.get("suggestedChart"));
        if (chart.isBlank()) chart = "table";
        if (!CHART_TYPES.contains(chart)) {
            throw new ApiException("非法图型: " + chart + "（允许: " + CHART_TYPES + "）");
        }

        // M3-3 取数绑定：ruleCode 可选，非空必须命中白名单注册表（fail-closed）；
        // ruleVersion 由注册表权威写入（不信客户端传值，口径变更靠版本钉定显式打断旧绑定）
        String ruleCode = str(m.get("ruleCode"));
        String ruleVersion = null;
        if (!ruleCode.isBlank()) {
            ruleVersion = ruleRegistry.require(ruleCode).version();
        }

        String source = str(m.get("source"));
        String origin = ("paragraph".equals(source) || "table".equals(source)) ? "extracted" : "custom";

        // 表内无列承载的明细（维度/章节/来源上下文/置信度）整体进 spec_json，供 M3-3 取数绑定与前端回显
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("dimension", str(m.get("dimension")));
        spec.put("section", str(m.get("section")));
        spec.put("subsection", str(m.get("subsection")));
        spec.put("suggestedChart", chart);
        spec.put("source", source);
        if (m.get("confidence") != null) spec.put("confidence", m.get("confidence"));
        if (m.get("sourceBlockIndex") != null) spec.put("sourceBlockIndex", m.get("sourceBlockIndex"));
        if (m.get("matchedText") != null) spec.put("matchedText", str(m.get("matchedText")));
        if (m.get("sampleValue") != null) spec.put("sampleValue", str(m.get("sampleValue")));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("templateId", templateId);
        row.put("metricCode", code);
        row.put("metricName", name);
        row.put("unit", unit);
        row.put("origin", origin);
        row.put("status", "draft");
        row.put("version", version);
        row.put("ruleCode", ruleCode.isBlank() ? null : ruleCode);
        row.put("ruleVersion", ruleVersion);
        row.put("specJson", writeJson(spec));
        return row;
    }

    /**
     * 自动编码的序号接续：max 形如 M009 则下一个是 10。
     * 历史行存在非 M 系编码（用户手改的自定义 code）时正则不匹配、回落 1——
     * 此时若 M001 已被占，靠 uk 冲突转 409 提示用户换码兜底，不会静默覆盖。
     *
     * @param maxCode maxMetricCode 查出的最大编码，可为 null（模板无任何指标行）
     * @return 下一个可用序号（从 1 起）
     */
    private int nextMetricSeq(String maxCode) {
        if (maxCode == null) return 1;
        Matcher m = METRIC_CODE_SEQ.matcher(maxCode.trim());
        return m.matches() ? Integer.parseInt(m.group(1)) + 1 : 1;
    }

    /**
     * 读模板的 spec_json 并反序列化为 Map（M3-2 各读路径共用）。
     *
     * @param specJson report_templates.spec_json 原文
     * @return 反序列化后的 Map
     * @throws ApiException 400 JSON 损坏（原文截记入 message，防 NPE 掩盖真实错误）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readSpec(String specJson) {
        try {
            return om.readValue(specJson, Map.class);
        } catch (Exception e) {
            throw new ApiException("模板 spec_json 解析失败: " + e.getMessage());
        }
    }

    /** null 安全转字符串并去首尾空白；null → 空串（校验/装配的统一入口） */
    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}