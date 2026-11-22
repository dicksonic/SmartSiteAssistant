package com.reg.assistant.report.service;

import com.dicksonic.gaios.context.Context;
import com.dicksonic.gaios.context.summarizer.impl.LlmSummarizer;
import com.dicksonic.gaios.context.tokenizer.impl.CharEstimateTokenizer;
import com.dicksonic.gaios.llm.LLMCore;
import com.dicksonic.gaios.llm.Message;
import com.dicksonic.gaios.llm.impl.DeepSeekCore;
import com.reg.assistant.auth.TokenService;
import com.reg.assistant.common.ApiException;
import com.reg.assistant.mapper.CallLogMapper;
import com.reg.assistant.mapper.ReportMapper;
import com.reg.assistant.model.Entities.AssistantSession;
import com.reg.assistant.report.ReportCoreHolder;
import com.reg.assistant.report.provider.ReportRule;
import com.reg.assistant.report.provider.ReportRuleRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M3-4 报告长文生成（复用 D6 滑动窗口压缩）。
 * <p>
 * 数据流：preview/failed 实例 → 读 report_data_blocks（M3-3 取数产物，带口径 meta 与 hash 指纹）
 * → 装配事实包 → 建 G-AIOS Context（CharEstimateTokenizer + LlmSummarizer，走真实 LLM 摘要）
 * → 固定四节骨架逐节生成（user 指令+数据 → assistant 正文入窗）→ 超阈值时早章节自动滑出、
 * 压缩成结构化摘要挂窗口头部，后续章节仍能"记得"前文结论（D6 复用点）
 * → 收尾洞察轮产要点 → 章节落 report_sections（refs 记录引用块的 hash，M3-5 回溯挂钩）
 * → 要点落 report_insights（summary 型）→ 状态 preview→generating→preview。
 * <p>
 * 工程语义（与 M3-3 start 同款）：
 * <ul>
 *   <li>编排是确定性主链，不进 Agent 循环（沿用 M2-2 视觉识别定案哲学）</li>
 *   <li>先全部生成完再动库：LLM 阶段失败不留半截章节；重新生成先清旧 sections 与旧 summary insights（幂等）</li>
 *   <li>失败 → generating→failed + error 落列（截 500 字符），可从 failed 重试</li>
 *   <li>计量：独立 DeepSeekCore 实例（ReportCoreHolder）+ UsageListener 按请求累加，
 *       压缩摘要的 token 与章节生成走同一 core、一并计入本次 call_logs（scenario=report）</li>
 * </ul>
 */
@Service
public class ReportGenerateService {

    private static final Logger log = LoggerFactory.getLogger(ReportGenerateService.class);

    /** 与 LLMCore 契约的哨兵约定："error"= 调用失败（内核永不抛异常） */
    private static final String FAIL_SENTINEL = "error";

    // call_logs 行标识：scenario=report 为 M3 新增值域（MySQL 5.7 静默忽略 CHECK 故本机可插；
    // 达梦/金仓强制 CHECK 时需补值域，已在 V3 文件头登记进 db/dm8 转换清单）
    private static final String SCENARIO = "report";
    private static final String API_TYPE = "text";
    private static final String PROVIDER = "gaios";
    /** DeepSeekCore 未暴露 model getter（同 QwenVLCore 的已知限制，D8 一并解决），先写常量 */
    private static final String MODEL = "deepseek";

    /**
     * 单块进入 prompt 的最大行数。
     * 取值依据：M3-3 首批规则的维度基数 < 50，20 行足以呈现趋势与排名；
     * 失效条件：高基数维度规则上线时上调，并同步复核 context-max-tokens 预算。
     */
    private static final int MAX_ROWS_IN_PROMPT = 20;
    /**
     * 单块摘要最大字符（防爆护栏，超出截断并标注）。
     * 取值依据：9 块 × 1200 字 ≈ 1.1 万字符，在默认 6000 token 预算内可由压缩链路消化；
     * 失效条件：规则数翻倍或块行均长显著变大时下调，或改分节喂数。
     */
    private static final int MAX_BLOCK_CHARS = 1200;
    /** 单实例要点洞察上限。取值依据：报告导读人一次能消化的要点 3~5 条，8 条封顶防模型刷屏；失效条件：页面上线导读区后按展示位调整 */
    private static final int MAX_INSIGHTS = 8;
    /** 洞察 severity 值域：与 V3 report_insights 的 CHECK 同口径（5.7 静默忽略，代码层强制） */
    private static final Set<String> SEVERITIES = Set.of("info", "warn", "critical");

    /**
     * 固定章节骨架（M3-4 四票拍板定案）。
     * domain="*"：概览节，喂全量块的紧凑摘要；domain=具体域：喂该域块的完整摘要；
     * domain=null：结论节，不喂新数据——靠前文窗口与压缩摘要生成，正是 D6 压缩的价值场。
     * 模板 section 推导的动态骨架列为 M3-5 后增强（M3-1 章节抽取质量未验证）。
     */
    private record SectionPlan(String code, String title, String domain, String requirement) {
    }

    private static final List<SectionPlan> SECTION_PLANS = List.of(
            new SectionPlan("OVERVIEW", "一、本期总体概述", "*",
                    "站在全局视角概述本期数据总体情况，点出最关键的三到五个数字并给出总体判断，不逐块罗列明细"),
            new SectionPlan("HAZARD_ANALYSIS", "二、隐患态势分析", "hazard",
                    "围绕隐患类数据块分析总量、分类构成、确认率与状态分布，指出需要重点关注的隐患类别"),
            new SectionPlan("PLATFORM_RUN", "三、平台运行分析", "platform",
                    "围绕平台运行类数据块分析调用量、token 消耗、成功率与场景分布，评价 AI 助手运行状况"),
            new SectionPlan("CONCLUSION", "四、结论与建议", null,
                    "不引入新数据，基于前文分析给出结论，并给出可执行的整改与运营建议")
    );

    /** 收尾洞察轮指令（生成完全部章节后追加一轮，产出进 report_insights） */
    private static final String INSIGHTS_PROMPT = """
            以上是报告全部章节。请基于全文产出 3~5 条要点洞察，严格输出 JSON 数组，不要输出任何其他内容：
            [{"text":"要点（含关键数字，50 字内）","severity":"info|warn|critical"}]
            severity 口径：critical=必须立即处置，warn=需要关注，info=一般提示。""";

    private final ReportCoreHolder reportCoreHolder;
    private final TokenService tokenService;
    private final ReportMapper reportMapper;
    private final ReportStatusMachine statusMachine;
    private final ReportRuleRegistry ruleRegistry;
    private final CallLogMapper callLogMapper;
    private final ObjectMapper om = new ObjectMapper();

    /** 按请求累加 token 的旁路（generate 与回调同线程，无需池化搬运，同 NoticeService） */
    private final ThreadLocal<int[]> usageAcc = new ThreadLocal<>();

    private final int contextMaxTokens;
    private final int keepRecent;

    public ReportGenerateService(ReportCoreHolder reportCoreHolder, TokenService tokenService,
                                 ReportMapper reportMapper, ReportStatusMachine statusMachine,
                                 ReportRuleRegistry ruleRegistry, CallLogMapper callLogMapper,
                                 @Value("${app.report.gen.context-max-tokens:6000}") int contextMaxTokens,
                                 @Value("${app.report.gen.keep-recent:4}") int keepRecent) {
        this.reportCoreHolder = reportCoreHolder;
        this.tokenService = tokenService;
        this.reportMapper = reportMapper;
        this.statusMachine = statusMachine;
        this.ruleRegistry = ruleRegistry;
        this.callLogMapper = callLogMapper;
        this.contextMaxTokens = contextMaxTokens;
        this.keepRecent = keepRecent;
    }

    @PostConstruct
    void attachUsageListener() {
        if (reportCoreHolder.available() && reportCoreHolder.core() instanceof DeepSeekCore ds) {
            ds.setUsageListener((prompt, completion, total) -> {
                int[] acc = usageAcc.get();
                if (acc != null) {
                    acc[0] += prompt;
                    acc[1] += completion;
                    acc[2] += total;
                }
            });
        }
    }

    /**
     * 长文生成主链：preview/failed → generating → preview。
     *
     * @param token      免登 token
     * @param instanceId 实例 id
     * @return {id, status, sections, insights, compressed, coveredMessages, latencyMs, usage}
     * @throws ApiException 400 实例无数据块/状态机拒绝/LLM 失败；404 实例不存在；功能降级时明确提示
     */
    public Map<String, Object> generate(String token, long instanceId) {
        AssistantSession session = tokenService.requireSession(token);
        if (!reportCoreHolder.available()) {
            throw new ApiException("报告长文生成未启用（缺少 deepseek.api.key 配置）");
        }
        Map<String, Object> inst = requireInstance(instanceId);
        String status = (String) inst.get("status");
        // 状态机决定谁能进 generating：preview=首次生成/重新生成，failed=失败重试；confirmed/exported 拒绝
        statusMachine.assertTransition(status, ReportStatusMachine.GENERATING);

        List<Map<String, Object>> blocks = reportMapper.listDataBlocksByInstance(instanceId);
        if (blocks.isEmpty()) {
            throw new ApiException("实例无数据块，请先执行取数（start）再生成文本: " + instanceId);
        }

        int n = reportMapper.transitionInstance(instanceId, status, ReportStatusMachine.GENERATING);
        if (n != 1) {
            throw new ApiException("报告状态已变化，请刷新后重试");
        }

        long t0 = System.currentTimeMillis();
        final int[] acc = new int[3];
        usageAcc.set(acc);
        try {
            LLMCore core = reportCoreHolder.core();
            // D6 复用点：压缩走 LlmSummarizer（真实 LLM 摘要），与章节生成共用同一 core——
            // 压缩 token 一并进入本次计量，D6 复用的真实成本在计量看板可见
            Context ctx = new Context(new CharEstimateTokenizer(),
                    new LlmSummarizer(prompt -> core.chat(List.of(new Message("user", prompt)))),
                    contextMaxTokens, keepRecent);
            ctx.addSystem(buildSystemPrompt(inst));

            List<Map<String, Object>> sectionRows = new ArrayList<>();
            int seq = 1;
            for (SectionPlan plan : SECTION_PLANS) {
                List<Map<String, Object>> related = relatedBlocks(plan, blocks);
                ctx.addUser(buildSectionPrompt(plan, related));
                String answer = core.chat(ctx.snapshot());
                if (answer == null || FAIL_SENTINEL.equals(answer)) {
                    throw new ApiException("章节生成失败（" + plan.title() + "），请稍后重试");
                }
                ctx.addAssistant(answer);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("instanceId", instanceId);
                row.put("sectionCode", plan.code());
                row.put("title", plan.title());
                row.put("seq", seq++);
                row.put("content", answer);
                // refs 挂钩"依据可回溯"：领域节引用本域块；CONCLUSION 建立在全文之上，引用全部块
                row.put("refsJson", writeJson(buildRefs(plan.domain() == null ? blocks : related)));
                sectionRows.add(row);
            }

            // 收尾洞察轮：基于全文（窗口原文 + 压缩摘要）产要点；解析失败降级原文单条，不丢信息
            ctx.addUser(INSIGHTS_PROMPT);
            String insightsRaw = core.chat(ctx.snapshot());
            if (insightsRaw == null || FAIL_SENTINEL.equals(insightsRaw)) {
                throw new ApiException("要点洞察生成失败，请稍后重试");
            }
            ctx.addAssistant(insightsRaw);
            List<Map<String, Object>> insights = parseInsights(insightsRaw);

            // 全部生成完才动库（LLM 阶段不留半截库状态）；重新生成先清场保幂等
            reportMapper.deleteSectionsByInstance(instanceId);
            reportMapper.deleteSummaryInsightsByInstance(instanceId);
            for (Map<String, Object> row : sectionRows) {
                reportMapper.insertSection(row);
            }
            for (Map<String, Object> in : insights) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("instanceId", instanceId);
                row.put("insightType", "summary");
                row.put("text", in.get("text"));
                row.put("refsJson", writeJson(Map.of("source", "m3-4-generate")));
                row.put("severity", in.get("severity"));
                reportMapper.insertInsight(row);
            }
            int m = reportMapper.transitionInstance(instanceId, ReportStatusMachine.GENERATING, ReportStatusMachine.PREVIEW);
            if (m != 1) {
                throw new ApiException("报告状态已变化，请刷新后重试");
            }

            long latencyMs = System.currentTimeMillis() - t0;
            logCall(session.getId(), acc, latencyMs, true);
            boolean compressed = !ctx.getSummary().isEmpty();
            log.info("[Report] 长文生成完成 instance={} 章节={} 洞察={} tokens={} 压缩触发={} 耗时={}ms",
                    instanceId, sectionRows.size(), insights.size(), acc[2], compressed, latencyMs);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", instanceId);
            result.put("status", ReportStatusMachine.PREVIEW);
            result.put("sections", sectionRows.size());
            result.put("insights", insights.size());
            // D6 演示叙事点：压缩是否真实触发、滑出了多少条原文进摘要
            result.put("compressed", compressed);
            result.put("coveredMessages", ctx.getSummary().getCoveredMessages());
            result.put("latencyMs", latencyMs);
            result.put("usage", Map.of("promptTokens", acc[0], "completionTokens", acc[1], "totalTokens", acc[2]));
            return result;
        } catch (RuntimeException e) {
            long latencyMs = System.currentTimeMillis() - t0;
            logCall(session.getId(), acc, latencyMs, false);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            reportMapper.transitionInstanceWithError(instanceId, ReportStatusMachine.GENERATING,
                    ReportStatusMachine.FAILED, msg.length() > 500 ? msg.substring(0, 500) : msg);
            throw e;
        } finally {
            usageAcc.remove();
        }
    }

    /** 实例章节列表（M3-4 读入口：前端预览 / M3-5 渲染导出的数据源） */
    public Map<String, Object> listSections(String token, long instanceId) {
        tokenService.requireSession(token);
        requireInstance(instanceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", instanceId);
        result.put("sections", reportMapper.listSectionsByInstance(instanceId));
        return result;
    }

    // ==================== 事实包装配 ====================

    /** 本节关联的数据块：CONCLUSION 不喂新数据；OVERVIEW 全量；其余按规则域过滤 */
    private List<Map<String, Object>> relatedBlocks(SectionPlan plan, List<Map<String, Object>> blocks) {
        if (plan.domain() == null) {
            return List.of();
        }
        if ("*".equals(plan.domain())) {
            return blocks;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> b : blocks) {
            if (plan.domain().equals(domainOf(b))) {
                out.add(b);
            }
        }
        return out;
    }

    /** 块 → 业务域：meta.ruleCode → 注册表；注册表缺该规则时归 "other" 并告警（防注册表演进打挂生成） */
    private String domainOf(Map<String, Object> block) {
        try {
            Map<String, Object> meta = readJsonMap((String) block.get("metaJson"));
            Optional<ReportRule> rule = ruleRegistry.find(str(meta.get("ruleCode")));
            return rule.map(ReportRule::domain).orElse("other");
        } catch (RuntimeException e) {
            log.warn("[Report] 数据块域识别失败 blockId={}: {}", block.get("blockId"), e.getMessage());
            return "other";
        }
    }

    /** 系统提示：角色 + 数据纪律（防编造护栏）+ 全局口径（时间/区域），整个生成会话只加一次 */
    private String buildSystemPrompt(Map<String, Object> inst) {
        Map<String, Object> params = readParams((String) inst.get("paramsJson"));
        String from = str(params.get("timeFrom"));
        String to = str(params.get("timeTo"));
        String timeRange = (from.isBlank() && to.isBlank()) ? "全部时间"
                : (from.isBlank() ? "..." : from) + " 至 " + (to.isBlank() ? "..." : to);
        String orgScope = str(params.get("orgScope"));
        return """
                你是住建监管数据分析报告的撰写助手，正在基于平台取数结果撰写一份综合分析报告。报告固定四节：一、本期总体概述；二、隐患态势分析；三、平台运行分析；四、结论与建议。
                数据纪律：
                1. 只允许依据对话中提供的数据块写作，每个关键数字都必须能在数据块中找到来源，禁止编造数据块之外的任何数字、案例或法规条文；
                2. 某领域无数据块时，如实说明"本期该域无数据"，不得虚构；
                3. 语言风格：政务报告口吻，结论先行，段落简洁，可用条目。
                报告期间：%s；区域范围：%s。
                每次只输出当前请求章节的正文（纯文本），不要输出 JSON，不要复述本指令。
                """.formatted(timeRange, orgScope.isBlank() ? "default" : orgScope);
    }

    /**
     * 分节 prompt：OVERVIEW 喂紧凑摘要（每块表头+前 3 行采样），
     * 领域节喂完整摘要（前 MAX_ROWS_IN_PROMPT 行），CONCLUSION 不带数据。
     */
    private String buildSectionPrompt(SectionPlan plan, List<Map<String, Object>> related) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(plan.title()).append("】\n");
        sb.append("写作要求：").append(plan.requirement())
                .append("。300~600 字，结论先行，关键数字必须与所给数据一致。\n");
        if (plan.domain() == null) {
            sb.append("（本节不提供新数据，请基于前文的窗口原文与摘要写作）");
            return sb.toString();
        }
        if (related.isEmpty()) {
            sb.append("（本域本期无数据块，请如实说明，不得编造）");
            return sb.toString();
        }
        boolean compact = "*".equals(plan.domain());
        sb.append("关联数据块（共 ").append(related.size()).append(" 块");
        if (compact) {
            sb.append("，以下为各块紧凑摘要");
        }
        sb.append("）：\n");
        for (Map<String, Object> b : related) {
            sb.append(compact ? compactDigest(b) : fullDigest(b)).append("\n");
        }
        return sb.toString();
    }

    /** 紧凑摘要（OVERVIEW 用）：一行头 + 最多 3 行采样，控制概览节 token 占用 */
    private String compactDigest(Map<String, Object> block) {
        Map<String, Object> meta = readJsonMap((String) block.get("metaJson"));
        List<List<Object>> rows = readRows((String) block.get("rowsJson"));
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(block.get("metricCode"))
                .append("（").append(str(meta.get("ruleName")))
                .append("，").append(str(meta.get("timeRange")))
                .append("，共 ").append(rows.size()).append(" 行）：列=")
                .append(block.get("columnsJson"));
        int show = Math.min(3, rows.size());
        for (int i = 0; i < show; i++) {
            sb.append("；").append(rows.get(i));
        }
        if (rows.size() > show) {
            sb.append("；...（仅采样前 ").append(show).append(" 行）");
        }
        return sb.toString();
    }

    /** 完整摘要（领域节用）：口径 + hash 指纹 + 列 + 前 N 行，超长按 MAX_BLOCK_CHARS 截断 */
    private String fullDigest(Map<String, Object> block) {
        Map<String, Object> meta = readJsonMap((String) block.get("metaJson"));
        List<List<Object>> rows = readRows((String) block.get("rowsJson"));
        String hash = str(block.get("hash"));
        StringBuilder sb = new StringBuilder();
        sb.append("【数据块 ").append(block.get("metricCode"))
                .append("｜").append(str(meta.get("ruleName"))).append("】\n");
        sb.append("口径：").append(str(meta.get("timeRange")))
                .append(" / ").append(str(meta.get("orgScope")))
                .append(" / 规则 ").append(str(meta.get("ruleCode")))
                .append(" v").append(str(meta.get("ruleVersion")))
                .append(" / 指纹 ").append(hash.length() > 12 ? hash.substring(0, 12) : hash).append("\n");
        sb.append("列：").append(block.get("columnsJson")).append("\n");
        int show = Math.min(MAX_ROWS_IN_PROMPT, rows.size());
        sb.append("数据（共 ").append(rows.size()).append(" 行，展示前 ").append(show).append(" 行）：\n");
        for (int i = 0; i < show; i++) {
            sb.append("  ").append(rows.get(i)).append("\n");
        }
        if (sb.length() > MAX_BLOCK_CHARS) {
            return sb.substring(0, MAX_BLOCK_CHARS) + "...[截断,原长" + sb.length() + "字符]";
        }
        return sb.toString();
    }

    /**
     * 本节 refs：记录引用了哪些数据块（blockId + metricCode + hash）。
     * hash 是 M3-3 的 SHA-256 内容指纹——M3-5 渲染端"依据可回溯"的验证锚点。
     */
    private List<Map<String, Object>> buildRefs(List<Map<String, Object>> related) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (Map<String, Object> b : related) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("blockId", b.get("blockId"));
            ref.put("metricCode", b.get("metricCode"));
            ref.put("hash", b.get("hash"));
            refs.add(ref);
        }
        return refs;
    }

    // ==================== 洞察解析 ====================

    /**
     * 洞察 JSON 解析：剥代码围栏 → 严格 JSON 数组 → 逐条校验 severity 值域。
     * 解析失败/空数组降级为"原文截断单条"（同通知单填充"留空交人工"的护栏哲学：不丢信息，也不让格式事故打挂主链）。
     */
    private List<Map<String, Object>> parseInsights(String raw) {
        try {
            String cleaned = raw;
            Matcher m = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```").matcher(raw);
            if (m.find()) {
                cleaned = m.group(1);
            }
            JsonNode node = om.readTree(cleaned.trim());
            if (node.isArray()) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (JsonNode item : node) {
                    String text = item.path("text").asText("").trim();
                    if (text.isEmpty()) {
                        continue;
                    }
                    String sev = item.path("severity").asText("info");
                    if (!SEVERITIES.contains(sev)) {
                        sev = "info";
                    }
                    out.add(Map.of("text", text, "severity", sev));
                    if (out.size() >= MAX_INSIGHTS) {
                        break;
                    }
                }
                if (!out.isEmpty()) {
                    return out;
                }
            }
        } catch (Exception e) {
            log.warn("[Report] 洞察 JSON 解析失败，降级为单条原文: {}", abbrev(raw));
        }
        String fallback = raw.length() > 500 ? raw.substring(0, 500) : raw;
        return List.of(Map.of("text", fallback, "severity", "info"));
    }

    // ==================== 共用 plumbing ====================

    private Map<String, Object> requireInstance(long id) {
        Map<String, Object> inst = reportMapper.findInstanceById(id);
        if (inst == null) {
            throw new ApiException(404, "报告实例不存在: " + id);
        }
        return inst;
    }

    /** 实例参数容错读取：params_json 在 start 已消费过，损坏不该发生；真坏了按空参数继续，不打挂生成 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return Map.of();
        }
        try {
            return om.readValue(paramsJson, Map.class);
        } catch (Exception e) {
            log.warn("[Report] 实例 params_json 解析失败，按空参数继续: {}", e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(String json) {
        try {
            return json == null || json.isBlank() ? Map.of() : om.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<List<Object>> readRows(String rowsJson) {
        try {
            return rowsJson == null || rowsJson.isBlank() ? List.of() : om.readValue(rowsJson, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeJson(Object v) {
        try {
            return om.writeValueAsString(v);
        } catch (Exception e) {
            throw new ApiException("报告生成产物序列化失败: " + e.getMessage());
        }
    }

    /** 计量写库失败不阻断主流程（计量是旁路，同 MeteredGateway 原则） */
    private void logCall(Long sessionId, int[] acc, long latencyMs, boolean success) {
        try {
            callLogMapper.insert(sessionId, SCENARIO, API_TYPE, PROVIDER, MODEL,
                    acc[2] / 1000.0, "千tokens", latencyMs, success);
        } catch (Exception e) {
            log.warn("[Report] call_logs 写入失败: {}", e.getMessage());
        }
    }

    /** 日志防刷屏截断 */
    private String abbrev(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    /** null 安全转字符串并去首尾空白；null → 空串 */
    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
