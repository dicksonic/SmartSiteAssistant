package com.reg.assistant.hazard.service;

import com.dicksonic.gaios.llm.vision.impl.QwenVLCore;
import com.dicksonic.gaios.llm.vision.VisionRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.reg.assistant.auth.TokenService;
import com.reg.assistant.common.ApiException;
import com.reg.assistant.hazard.HazardImageFetcher;
import com.reg.assistant.hazard.VisionCoreHolder;
import com.reg.assistant.mapper.CallLogMapper;
import com.reg.assistant.mapper.RecordsMapper;
import com.reg.assistant.model.Entities.AssistantSession;
import com.reg.assistant.model.Entities.HazardDraft;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 隐患图像识别主链（M2-2）：取图 → VL 识别 → 结构化草稿 → hazard_records 待确认。
 * 架构决策：本链是确定性编排，不进 Agent 循环——识别是单步工具调用，
 * 没有多轮推理需求；Agent 只在 M2-3 用户对草稿发起追问时进入（查条文/拟通知单）。
 *
 * 计量：与 AgentService 同构的 UsageListener+ThreadLocal 旁路。
 * 比 AgentService 简单的是 recognize() 同步执行于请求线程，fireUsage 同线程回调，
 * 无需 worker 线程池搬运 ThreadLocal。
 */
@Service
public class HazardRecognizeService {

    private static final Logger log = LoggerFactory.getLogger(HazardRecognizeService.class);

    /** 与 VisionCore 契约的哨兵约定："error"= 调用失败（内核永不抛异常） */
    private static final String FAIL_SENTINEL = "error";

    // call_logs 行标识：api_type=vision 在 V1 CHECK 值域内（text/vision/asr/tts），无需迁移
    private static final String SCENARIO = "hazard";
    private static final String API_TYPE = "vision";
    private static final String PROVIDER = "dashscope";
    /** QwenVLCore 未暴露 model getter（同 DeepSeekCore 的已知限制，D8 一并解决），先写常量 */
    private static final String MODEL = "qwen-vl";

    /**
     * 识别 prompt：严格 JSON 输出 + 无隐患时的显式出口。
     * category 值域给了封闭枚举——自由发挥的分类名会让后续统计/台账对接失锚。
     */
    private static final String RECOGNIZE_PROMPT = """
            你是建筑施工安全隐患识别专家。观察这张施工现场照片，识别其中存在的安全隐患。
            只输出一个 JSON 对象，不要输出 markdown 代码块或任何解释文字：
            {"category":"隐患分类（高处作业/临时用电/脚手架/基坑工程/起重机械/模板支撑/消防/文明施工/其他，单选）",
             "location":"从画面可判断的部位，无法判断填空字符串",
             "description":"符合《建筑施工安全检查标准》JGJ59 表述习惯的隐患描述，一句话",
             "rectification":"整改要求，一句话",
             "basis":"主要依据条文（不确定填 JGJ59）",
             "confidence":"high|medium|low"}
            若画面中没有可见安全隐患，输出 {"category":"无","location":"","description":"未识别到明显安全隐患","rectification":"","basis":"","confidence":"low"}。
            """;

    private final VisionCoreHolder visionHolder;
    private final HazardImageFetcher imageFetcher;
    private final TokenService tokenService;
    private final RecordsMapper recordsMapper;
    private final CallLogMapper callLogMapper;
    private final ObjectMapper om = new ObjectMapper();

    /** 按请求累加 token 的旁路（recognize 与回调同线程，无需池化搬运） */
    private final ThreadLocal<int[]> usageAcc = new ThreadLocal<>();

    public HazardRecognizeService(VisionCoreHolder visionHolder, HazardImageFetcher imageFetcher,
                                  TokenService tokenService, RecordsMapper recordsMapper,
                                  CallLogMapper callLogMapper) {
        this.visionHolder = visionHolder;
        this.imageFetcher = imageFetcher;
        this.tokenService = tokenService;
        this.recordsMapper = recordsMapper;
        this.callLogMapper = callLogMapper;
    }

    @PostConstruct
    void attachUsageListener() {
        if (visionHolder.available() && visionHolder.core() instanceof QwenVLCore qwen) {
            qwen.setUsageListener((prompt, completion, total) -> {
                int[] acc = usageAcc.get();
                if (acc != null) {
                    acc[0] += prompt;
                    acc[1] += completion;
                    acc[2] += total;
                }
            });
        }
    }

    /** 样本图片清单（本地模拟期前端挑选器数据源） */
    public List<HazardImageFetcher.ImageItem> listImages(String token) {
        tokenService.requireSession(token);
        return imageFetcher.list();
    }

    /** 预览图取数（前端缩略图）：复用 fetch 的全部校验 */
    public Path imagePath(String token, String imageName) {
        tokenService.requireSession(token);
        return imageFetcher.fetch(imageName);
    }

    /**
     * 识别主链：成功返回草稿（含 id/status=pending_confirm）；VL 调用失败抛业务异常。
     * 无论成败，call_logs 都有一行 vision 计量。
     */
    public Map<String, Object> recognize(String token, String imageName, String location) {
        AssistantSession session = tokenService.requireSession(token);
        if (!visionHolder.available()) {
            throw new ApiException("隐患图像识别未启用（缺少 vision.api.key 配置）");
        }
        Path image = imageFetcher.fetch(imageName);

        long start = System.currentTimeMillis();
        final int[] acc = new int[3];
        usageAcc.set(acc);
        final String raw;
        try {
            raw = visionHolder.core().recognize(new VisionRequest(image, RECOGNIZE_PROMPT));
        } finally {
            usageAcc.remove();
        }
        long latencyMs = System.currentTimeMillis() - start;

        boolean success = raw != null && !FAIL_SENTINEL.equals(raw);
        logCall(session.getId(), acc, latencyMs, success);
        if (!success) {
            throw new ApiException("图像识别调用失败，请稍后重试");
        }

        ObjectNode parsed = extractJson(raw);
        if (parsed == null) {
            // 模型没按约定输出 JSON：原文进 description 交人工兜底，绝不让识别结果丢失
            log.warn("[HazardRecognize] VL 输出非 JSON，原文降级进 description: {}", abbrev(raw));
            parsed = om.createObjectNode();
            parsed.put("category", "其他");
            parsed.put("location", "");
            parsed.put("description", raw);
            parsed.put("rectification", "按规范要求限期整改");
            parsed.put("basis", "JGJ59");
            parsed.put("confidence", "low");
        }
        // 用户指定部位优先于 VL 判断（人比模型更知道自己在哪拍的）
        String loc = (location != null && !location.isBlank())
                ? location : parsed.path("location").asText("");

        HazardDraft draft = new HazardDraft();
        draft.setSessionId(session.getId());
        draft.setInputMode("photo");
        draft.setHasImage(true);
        draft.setTranscript(null);
        draft.setCategory(parsed.path("category").asText("其他"));
        draft.setLocation(loc);
        draft.setDescription(parsed.path("description").asText());
        draft.setRectification(parsed.path("rectification").asText(""));
        draft.setBasis(parsed.path("basis").asText(""));
        draft.setStatus("pending_confirm");
        recordsMapper.insertHazardDraft(draft);

        log.info("[HazardRecognize] 识别完成 image={} id={} category={} confidence={} tokens={} 耗时={}ms",
                imageName, draft.getId(), draft.getCategory(),
                parsed.path("confidence").asText("-"), acc[2], latencyMs);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", draft.getId());
        result.put("imageName", imageName);
        result.put("category", draft.getCategory());
        result.put("location", draft.getLocation());
        result.put("description", draft.getDescription());
        result.put("rectification", draft.getRectification());
        result.put("basis", draft.getBasis());
        result.put("confidence", parsed.path("confidence").asText(""));
        result.put("status", draft.getStatus());
        result.put("latencyMs", latencyMs);
        result.put("usage", Map.of("promptTokens", acc[0], "completionTokens", acc[1], "totalTokens", acc[2]));
        return result;
    }

    /**
     * 人工确认：可携带修正后的字段（识别草稿本来就预期被改一遍）。
     * 更新 0 行 = 记录不存在或已被确认，状态机守卫在 SQL 层，无需先查后改。
     */
    public Map<String, Object> confirm(String token, long hazardId, String category, String location,
                                       String description, String rectification, String basis) {
        tokenService.requireSession(token);
        int rows = recordsMapper.confirmHazard(hazardId, category, location, description, rectification, basis);
        if (rows == 0) {
            throw new ApiException("确认失败：记录不存在或已确认");
        }
        log.info("[HazardRecognize] 隐患记录已确认 id={}", hazardId);
        return Map.of("id", hazardId, "status", "confirmed");
    }

    /** 计量写库失败不阻断主流程（同 MeteredGateway 原则：计量是旁路） */
    private void logCall(Long sessionId, int[] acc, long latencyMs, boolean success) {
        try {
            callLogMapper.insert(sessionId, SCENARIO, API_TYPE, PROVIDER, MODEL,
                    acc[2] / 1000.0, "千tokens", latencyMs, success);
        } catch (Exception e) {
            log.warn("[HazardRecognize] call_logs 写入失败: {}", e.getMessage());
        }
    }

    /** 从模型输出中提取 JSON（与 AssistantService 同款容错；两处私有副本，统一收口列入 D8） */
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

    private static String abbrev(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}