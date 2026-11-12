package com.reg.assistant.notice.service;

import com.deepoove.poi.XWPFTemplate;
import com.dicksonic.gaios.llm.Message;
import com.dicksonic.gaios.llm.impl.DeepSeekCore;
import com.reg.assistant.auth.TokenService;
import com.reg.assistant.common.ApiException;
import com.reg.assistant.mapper.CallLogMapper;
import com.reg.assistant.mapper.RecordsMapper;
import com.reg.assistant.model.Entities.AssistantSession;
import com.reg.assistant.model.Entities.HazardDraft;
import com.reg.assistant.notice.NoticeTemplateProvider;
import com.reg.assistant.notice.TextCoreHolder;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 通知单模板化主链（M2-4）：模板槽位解析 → LLM 只产槽位 JSON → 人工编辑 → poi-tl 确定性渲染。
 * 架构决策（与"视觉识别不进 Agent 循环"同哲学）：
 * - LLM 的角色是"填表员"，只输出 {占位符: 值}，渲染与它无关；
 * - 渲染在主链用 poi-tl 做 {{tag}} 替换，确定性、不计量；
 * - 槽位动态对齐：换模板零代码——槽位集合从 docx 正文实时解析，填充 prompt 随之生成。
 * 已知限制：页眉页脚的标签不解析（只读 word/document.xml）；
 * Word 跨 run 拆开的标签提取不到（平台模板既在打印使用，说明标签完好，风险低）。
 */
@Service
public class NoticeService {

    private static final Logger log = LoggerFactory.getLogger(NoticeService.class);

    /** 与 LLMCore 契约的哨兵约定："error"= 调用失败（内核永不抛异常） */
    private static final String FAIL_SENTINEL = "error";

    // call_logs 行标识：api_type=text 在 V1 CHECK 值域内，无需迁移
    private static final String SCENARIO = "hazard";
    private static final String API_TYPE = "text";
    private static final String PROVIDER = "gaios";
    /** DeepSeekCore 未暴露 model getter（同 QwenVLCore 的已知限制，D8 一并解决），先写常量 */
    private static final String MODEL = "deepseek";

    /** poi-tl 文本标签 {{var}}；特殊标签（区块/图片/表格等，首字符非文字）本期不填充 */
    private static final Pattern SLOT_PATTERN = Pattern.compile("\\{\\{([^{}]+)}}");

    private final TextCoreHolder textHolder;
    private final NoticeTemplateProvider templateProvider;
    private final TokenService tokenService;
    private final RecordsMapper recordsMapper;
    private final CallLogMapper callLogMapper;
    private final ObjectMapper om = new ObjectMapper();

    /** 按请求累加 token 的旁路（fill 与回调同线程，无需池化搬运，同 HazardRecognizeService） */
    private final ThreadLocal<int[]> usageAcc = new ThreadLocal<>();

    public NoticeService(TextCoreHolder textHolder, NoticeTemplateProvider templateProvider,
                         TokenService tokenService, RecordsMapper recordsMapper,
                         CallLogMapper callLogMapper) {
        this.textHolder = textHolder;
        this.templateProvider = templateProvider;
        this.tokenService = tokenService;
        this.recordsMapper = recordsMapper;
        this.callLogMapper = callLogMapper;
    }

    @PostConstruct
    void attachUsageListener() {
        if (textHolder.available() && textHolder.core() instanceof DeepSeekCore ds) {
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

    public record TemplateView(String name, long sizeKb, List<String> slots) {}
    public record RenderedDoc(String filename, byte[] content) {}

    /** 模板清单（含槽位集合）：前端挑选器数据源 */
    public List<TemplateView> listTemplates(String token) {
        tokenService.requireSession(token);
        List<TemplateView> views = new ArrayList<>();
        for (NoticeTemplateProvider.TemplateItem item : templateProvider.list()) {
            List<String> slots;
            try {
                slots = extractSlots(templateProvider.load(item.name()));
            } catch (Exception e) {
                log.warn("[Notice] 模板槽位解析失败 {}: {}", item.name(), e.getMessage());
                slots = List.of();
            }
            views.add(new TemplateView(item.name(), item.sizeKb(), slots));
        }
        return views;
    }

    /**
     * 填充主链：载入已确认隐患 → 解析模板槽位 → 动态生成填充 prompt → LLM 只产槽位 JSON。
     * 两道护栏：输出 key 不在槽位集合的丢弃（防编造槽位）；缺的槽位补空串（形态完整交给人工）。
     * 无论成败，call_logs 都有一行 text 计量。
     */
    public Map<String, Object> fill(String token, long hazardId, String templateName) {
        AssistantSession session = tokenService.requireSession(token);
        if (!textHolder.available()) {
            throw new ApiException("通知单填充未启用（缺少 deepseek.api.key 配置）");
        }
        HazardDraft hazard = recordsMapper.findHazardById(hazardId);
        if (hazard == null) {
            throw new ApiException("隐患记录不存在: " + hazardId);
        }
        // 只对已确认记录出单：草稿态数据未经人工核对，出了单就是事故
        if (!"confirmed".equals(hazard.getStatus())) {
            throw new ApiException("通知单只能针对已确认的隐患记录生成（当前状态: " + hazard.getStatus() + "）");
        }
        List<String> slots = extractSlots(templateProvider.load(templateName));
        if (slots.isEmpty()) {
            throw new ApiException("模板未解析到任何 {{占位符}}，请检查模板: " + templateName);
        }

        String prompt = buildFillPrompt(slots, hazard);
        long start = System.currentTimeMillis();
        final int[] acc = new int[3];
        usageAcc.set(acc);
        final String raw;
        try {
            raw = textHolder.core().chat(List.of(new Message("user", prompt)));
        } finally {
            usageAcc.remove();
        }
        long latencyMs = System.currentTimeMillis() - start;

        boolean success = raw != null && !FAIL_SENTINEL.equals(raw);
        logCall(session.getId(), acc, latencyMs, success);
        if (!success) {
            throw new ApiException("通知单填充调用失败，请稍后重试");
        }

        ObjectNode parsed = extractJson(raw);
        if (parsed == null) {
            log.warn("[Notice] LLM 输出非 JSON，全部槽位留空交人工: {}", abbrev(raw));
            parsed = om.createObjectNode();
        }
        // 护栏落点：以槽位集合为准收敛输出——多出来的 key 丢弃，缺的补空串
        Map<String, String> values = new LinkedHashMap<>();
        for (String slot : slots) {
            values.put(slot, parsed.path(slot).asText(""));
        }

        log.info("[Notice] 填充完成 template={} hazardId={} slots={} tokens={} 耗时={}ms",
                templateName, hazardId, slots.size(), acc[2], latencyMs);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateName", templateName);
        result.put("slots", slots);
        result.put("values", values);
        result.put("latencyMs", latencyMs);
        result.put("usage", Map.of("promptTokens", acc[0], "completionTokens", acc[1], "totalTokens", acc[2]));
        return result;
    }

    /**
     * 渲染主链：poi-tl 纯替换，零 LLM、不计量。
     * values 同样按槽位集合收敛——前端可编辑后的值原样回来，但只认模板里有的槽位。
     */
    public RenderedDoc render(String token, String templateName, Map<String, String> values) {
        tokenService.requireSession(token);
        Path template = templateProvider.load(templateName);
        List<String> slots = extractSlots(template);
        Map<String, Object> data = new LinkedHashMap<>();
        for (String slot : slots) {
            data.put(slot, values != null ? values.getOrDefault(slot, "") : "");
        }
        try (XWPFTemplate doc = XWPFTemplate.compile(template.toFile());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.render(data);
            doc.write(out);
            String base = template.getFileName().toString().replaceFirst("(?i)\\.docx$", "");
            log.info("[Notice] 渲染完成 template={} slots={}", templateName, slots.size());
            return new RenderedDoc(base + "-已填充.docx", out.toByteArray());
        } catch (IOException e) {
            throw new ApiException("通知单渲染失败: " + e.getMessage());
        }
    }

    /** 办公人员上传模板（同名覆盖）：四防线在 provider.store 内 */
    public Map<String, Object> upload(String token, String originalFilename, byte[] content) {
        tokenService.requireSession(token);
        Path saved = templateProvider.store(originalFilename, content);
        log.info("[Notice] 模板已上传: {}", saved.getFileName());
        return Map.of("name", saved.getFileName().toString(), "sizeKb", content.length / 1024);
    }

    /**
     * 槽位动态对齐核心：解 docx 读 word/document.xml，正则提取 {{tag}}。
     * 首字符过滤掉 poi-tl 特殊标签（@图片 #表格 ?条件 /闭合 等），本期只填文本槽位。
     */
    private List<String> extractSlots(Path docx) {
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            ZipEntry entry = zip.getEntry("word/document.xml");
            if (entry == null) {
                throw new ApiException("不是有效的 docx 模板（缺 word/document.xml）");
            }
            String xml;
            try (InputStream in = zip.getInputStream(entry)) {
                xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Set<String> slots = new LinkedHashSet<>();
            Matcher m = SLOT_PATTERN.matcher(xml);
            while (m.find()) {
                String tag = m.group(1).trim();
                if (tag.isEmpty()) {
                    continue;
                }
                char first = tag.charAt(0);
                if ("@#?/=!+-*~".indexOf(first) >= 0) {
                    continue; // poi-tl 特殊标签本期不填充
                }
                slots.add(tag);
            }
            return List.copyOf(slots);
        } catch (IOException e) {
            throw new ApiException("模板解析失败: " + e.getMessage());
        }
    }

    /**
     * 填充 prompt 随槽位集合动态生成——换模板零代码的落点。
     * 关键约束：上下文无法得知的槽位一律留空、禁止编造——编号/日期/单位类
     * 在目标架构里由业务系统打印时补（M4 定案），本地 demo 里靠人工编辑兜底。
     */
    private String buildFillPrompt(List<String> slots, HazardDraft hazard) {
        return """
                你是建设工程监管文书助手。根据下面这条已确认的隐患记录，为整改通知单模板的占位符填写内容。

                隐患记录：
                - 隐患分类：%s
                - 隐患部位：%s
                - 隐患描述：%s
                - 整改要求：%s
                - 依据条文：%s

                模板占位符共 %d 个：%s

                要求：
                1. 只输出一个 JSON 对象，不要 markdown 代码块或解释文字；key 必须且只能是上面列出的占位符名（不带花括号），value 为字符串；
                2. 能由隐患记录得出的槽位据实填写，表述符合监管文书习惯（简洁、正式）；
                3. 隐患记录中无法得知的信息（如发文编号、发文日期、工程名称、单位名称）一律填空字符串 ""，禁止编造；
                4. 整改期限类槽位可依据整改要求给出合理建议（如"三日内"），拿不准填空字符串。
                """.formatted(
                hazard.getCategory(), nullToEmpty(hazard.getLocation()),
                hazard.getDescription(), nullToEmpty(hazard.getRectification()),
                nullToEmpty(hazard.getBasis()),
                slots.size(), String.join(", ", slots));
    }

    /** 计量写库失败不阻断主流程（计量是旁路，同 MeteredGateway 原则） */
    private void logCall(Long sessionId, int[] acc, long latencyMs, boolean success) {
        try {
            callLogMapper.insert(sessionId, SCENARIO, API_TYPE, PROVIDER, MODEL,
                    acc[2] / 1000.0, "千tokens", latencyMs, success);
        } catch (Exception e) {
            log.warn("[Notice] call_logs 写入失败: {}", e.getMessage());
        }
    }

    /** 从模型输出中提取 JSON（同款容错第三处副本——extractJson 统一收口列入 D8） */
    private ObjectNode extractJson(String raw) {
        try {
            String cleaned = raw;
            Matcher m = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```").matcher(raw);
            if (m.find()) cleaned = m.group(1);
            JsonNode node = om.readTree(cleaned.trim());
            return node.isObject() ? (ObjectNode) node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String abbrev(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
