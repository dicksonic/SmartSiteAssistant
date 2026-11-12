package com.reg.assistant.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.reg.assistant.agent.service.KnowledgeSearchService;
import com.reg.assistant.auth.TokenService;
import com.reg.assistant.common.ApiException;
import com.reg.assistant.mapper.KnowledgeMapper;
import com.reg.assistant.mapper.RecordsMapper;
import com.reg.assistant.model.Entities.AssistantSession;
import com.reg.assistant.model.Entities.KnowledgeItem;
import com.reg.assistant.provider.MeteredGateway;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 四大场景业务服务：与Node版 assistantRouter.ts 逻辑一致（prompt 原样平移）。
 * 所有模型调用经 MeteredGateway 计量。
 */
@Service
public class AssistantService {

    private final TokenService tokenService;
    private final MeteredGateway gateway;
    private final RecordsMapper recordsMapper;
    private final ObjectMapper om = new ObjectMapper();
    private final KnowledgeSearchService knowledgeSearchService;


    public AssistantService(TokenService tokenService, MeteredGateway gateway, RecordsMapper recordsMapper,KnowledgeSearchService knowledgeSearchService) {
        this.tokenService = tokenService;
        this.gateway = gateway;
        this.recordsMapper = recordsMapper;
        this.knowledgeSearchService = knowledgeSearchService;
    }

    /** 场景① 规范条文问答 */
    public Map<String, Object> ask(String token, String question, String audioBase64) throws Exception {
        AssistantSession session = tokenService.requireSession(token);

        String q = question;
        String asrText = null;
        if ((q == null || q.isBlank()) && audioBase64 != null && !audioBase64.isBlank()) {
            var asr = gateway.execute("qa", session.getId(),
                    () -> gateway.provider().asr(audioBase64), "asr", "秒");
            q = asr.data;
            asrText = asr.data;
        }
        if (q == null || q.isBlank()) throw new ApiException("请提供问题文本或语音");

        List<KnowledgeItem> items = retrieveKnowledge(q);
        List<String> context = items.stream().map(i -> "【" + i.getTitle() + "】" + i.getContent()).toList();

        final String finalQ = q;
        var chat = gateway.execute("qa", session.getId(),
                () -> gateway.provider().chat(
                        "你是工程现场检查助手。基于给定规范条文回答，输出严格JSON：{\"answer\": string, \"checklist\": string[], \"references\": string[]}。checklist为可执行的检查清单，references为引用条文名称。",
                        finalQ, context),
                "text", "千tokens");
        ObjectNode parsed = extractJson(chat.data);
        if (parsed == null) {
            parsed = om.createObjectNode();
            parsed.put("answer", chat.data);
            parsed.putArray("checklist");
            var refs = parsed.putArray("references");
            items.forEach(i -> refs.add(i.getTitle()));
        }

        recordsMapper.insertQa(session.getId(), q, om.writeValueAsString(parsed));

        Map<String, Object> result = om.convertValue(parsed, Map.class);
        result.put("question", q);
        result.put("asrText", asrText);
        int checklistSize = parsed.has("checklist") ? parsed.get("checklist").size() : 0;
        result.put("ttsText", parsed.path("answer").asText() + " 共" + checklistSize + "项检查要点。");
        return result;
    }

    /** 场景② 隐患智能描述 */
    public Map<String, Object> describeHazard(String token, String imageBase64, String text,
                                              String audioBase64, String location) throws Exception {
        AssistantSession session = tokenService.requireSession(token);
        if ((imageBase64 == null || imageBase64.isBlank()) && (text == null || text.isBlank())
                && (audioBase64 == null || audioBase64.isBlank())) {
            throw new ApiException("请提供照片、语音或文字描述");
        }

        String visionObs = "";
        if (imageBase64 != null && !imageBase64.isBlank()) {
            var v = gateway.execute("hazard", session.getId(),
                    () -> gateway.provider().vision(imageBase64,
                            "识别图片中的施工现场隐患，输出JSON：{\"objects\": string[], \"scene\": string, \"riskLevel\": string}"),
                    "vision", "次");
            visionObs = v.data;
        }

        String transcript = text != null ? text : "";
        if (transcript.isBlank() && audioBase64 != null && !audioBase64.isBlank()) {
            var asr = gateway.execute("hazard", session.getId(),
                    () -> gateway.provider().asr(audioBase64), "asr", "秒");
            transcript = asr.data;
        }

        final String prompt = "图像识别：" + (visionObs.isBlank() ? "（无照片）" : visionObs)
                + "\n口述/文字：" + (transcript.isBlank() ? "（无）" : transcript)
                + "\n部位：" + (location != null ? location : "未指定");
        var chat = gateway.execute("hazard", session.getId(),
                () -> gateway.provider().chat(
                        "你是安全隐患记录员。根据图像识别结果与口述内容，生成符合《建筑施工安全检查标准》JGJ59表述习惯的隐患描述与整改要求。输出严格JSON：{\"category\": string, \"location\": string, \"description\": string, \"rectification\": string, \"basis\": string}。",
                        prompt, List.of()),
                "text", "千tokens");
        ObjectNode parsed = extractJson(chat.data);
        if (parsed == null) {
            parsed = om.createObjectNode();
            parsed.put("category", "其他");
            parsed.put("location", location != null ? location : "未指定");
            parsed.put("description", transcript.isBlank() ? chat.data : transcript);
            parsed.put("rectification", "按规范要求限期整改");
            parsed.put("basis", "JGJ59");
        }

        String inputMode = imageBase64 != null && !imageBase64.isBlank() ? "photo"
                : (audioBase64 != null && !audioBase64.isBlank() && (text == null || text.isBlank())) ? "voice" : "text";
        recordsMapper.insertHazard(session.getId(), inputMode, imageBase64 != null && !imageBase64.isBlank(),
                transcript.isBlank() ? null : transcript,
                parsed.path("category").asText(), parsed.path("location").asText(),
                parsed.path("description").asText(), parsed.path("rectification").asText(),
                parsed.path("basis").asText());
        return om.convertValue(parsed, Map.class);
    }

    /** 场景③ 材料智能预审 */
    public Map<String, Object> reviewMaterial(String token, String materialName, String docType,
                                              List<Map<String, Object>> pages) throws Exception {
        AssistantSession session = tokenService.requireSession(token);

        StringBuilder docText = new StringBuilder();
        for (Map<String, Object> p : pages) {
            docText.append("[第").append(p.get("pageNo")).append("页] ").append(p.get("text")).append("\n");
        }

        var chat = gateway.execute("material", session.getId(),
                () -> gateway.provider().chat(
                        "你是工程资料审核员。检查材料的完整性（必备章节/签章/日期是否缺失）与逻辑一致性（前后数据、日期、工程量是否矛盾）。输出严格JSON：{\"missingItems\": string[], \"logicIssues\": [{\"location\": string, \"issue\": string, \"suggestion\": string}], \"verdict\": \"pass\"|\"conditional\"|\"reject\"}。",
                        "材料名称：" + materialName + "\n材料类型：" + docType + "\n内容：\n" + docText, List.of()),
                "text", "千tokens");
        ObjectNode parsed = extractJson(chat.data);
        if (parsed == null) {
            parsed = om.createObjectNode();
            parsed.putArray("missingItems");
            parsed.putArray("logicIssues");
            parsed.put("verdict", "conditional");
        }

        recordsMapper.insertMaterial(session.getId(), materialName, docType, pages.size(),
                om.writeValueAsString(parsed.path("missingItems")),
                om.writeValueAsString(parsed.path("logicIssues")),
                parsed.path("verdict").asText("conditional"));
        return om.convertValue(parsed, Map.class);
    }

    /** 场景④ 文书材料比对 */
    public Map<String, Object> compareRectification(String token, String beforeText, String afterText,
                                                    String beforeImageBase64, String afterImageBase64) throws Exception {
        AssistantSession session = tokenService.requireSession(token);

        Double photoSimScore = null;
        if (beforeImageBase64 != null && !beforeImageBase64.isBlank()
                && afterImageBase64 != null && !afterImageBase64.isBlank()) {
            gateway.execute("compare", session.getId(),
                    () -> gateway.provider().vision(beforeImageBase64, "描述该施工现场照片中的隐患状态，100字内"),
                    "vision", "次");
            gateway.execute("compare", session.getId(),
                    () -> gateway.provider().vision(afterImageBase64, "描述该施工现场照片中的隐患状态，100字内"),
                    "vision", "次");
            photoSimScore = 0.85; // 正式版可替换为图像向量相似度
        }

        final String prompt = "整改前描述：" + beforeText + "\n整改后描述：" + afterText
                + (photoSimScore != null ? "\n照片比对参考相似度：" + photoSimScore : "");
        var chat = gateway.execute("compare", session.getId(),
                () -> gateway.provider().chat(
                        "你是整改复核员。比对隐患整改前后的描述（与照片分析），判断整改是否到位。输出严格JSON：{\"textConsistent\": boolean, \"verdict\": \"rectified\"|\"partial\"|\"not_rectified\", \"conclusion\": string}。",
                        prompt, List.of()),
                "text", "千tokens");
        ObjectNode parsed = extractJson(chat.data);
        if (parsed == null) {
            parsed = om.createObjectNode();
            parsed.put("textConsistent", false);
            parsed.put("verdict", "partial");
            parsed.put("conclusion", chat.data);
        }
        parsed.put("photoSimScore", photoSimScore);

        recordsMapper.insertComparison(session.getId(), beforeText, afterText, photoSimScore != null,
                photoSimScore, parsed.path("textConsistent").asBoolean(false),
                parsed.path("verdict").asText("partial"), parsed.path("conclusion").asText());
        return om.convertValue(parsed, Map.class);
    }

    /** 语音合成：mock/未接入时返回null，前端降级浏览器播报；调用量照常计量 */
    public Map<String, Object> synthesize(String token, String text) {
        AssistantSession session = tokenService.requireSession(token);
        String audio = null;
        try {
            var res = gateway.execute("qa", session.getId(),
                    () -> gateway.provider().tts(text), "tts", "字符");
            audio = res.data;
        } catch (Exception ignored) {
            // 真实TTS未接入时不阻断体验
        }
        return Map.of("audioBase64", audio == null ? "" : audio);
    }

    // ---------- 内部方法 ----------

    /** 规范条文检索：E5 向量语义检索（M1-5），失败自动降级关键词 LIKE */
    private List<KnowledgeItem> retrieveKnowledge(String question) {
        return knowledgeSearchService.search(question, 5);
    }
    /** 规范条文检索（预研版：关键词匹配；正式版替换为向量检索） */
    /*private List<KnowledgeItem> retrieveKnowledge(String question) {
        List<String> keywords = new ArrayList<>();
        Matcher m = Pattern.compile("[\\u4e00-\\u9fa5]{2,}").matcher(question);
        while (m.find() && keywords.size() < 4) keywords.add(m.group());
        if (keywords.isEmpty()) return knowledgeMapper.findAll(5);
        List<KnowledgeItem> found = knowledgeMapper.searchByKeywords(keywords, 5);
        return found.isEmpty() ? knowledgeMapper.findAll(5) : found;
    }*/

    /** 从模型输出中提取JSON（容错：模型可能包裹在markdown代码块中） */
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
}
