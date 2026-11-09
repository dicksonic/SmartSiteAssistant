package com.reg.assistant.agent;

import com.dicksonic.gaios.tools.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reg.assistant.mapper.RecordsMapper;
import com.reg.assistant.model.Entities.HazardDraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 隐患台账查询工具（M2-3）：让 Agent 能查 hazard_records 业务库。
 * 与 RegulationSearchTool 同批在 GaiosConfig 注册、同走三阶段安检。
 *
 * description 自律说明：注册时这段文字要过自家 LLM 仲裁（规则 5 指令性语言判据），
 * 因此全段保持文档风格——只说"能查什么、参数怎么填、返回什么"，
 * 不出现"建议/务必/优先"等安排 Agent 行为的措辞。被自家安检器拦下的工具不是笑话，是事故。
 */
public class HazardDraftTool implements Tool {

    private static final Logger log = LogManager.getLogger(HazardDraftTool.class);

    /** 单次返回上限。取值依据同 RegulationSearchTool：约束回喂模型的上下文体积 */
    private static final int LIMIT = 5;
    private final ObjectMapper om = new ObjectMapper();

    private final RecordsMapper recordsMapper;

    public HazardDraftTool(RecordsMapper recordsMapper) {
        this.recordsMapper = recordsMapper;
    }

    @Override
    public String name() { return "query_hazards"; }

    @Override
    public String description() {
        // 警告（同 Tool 接口约定）：这段文字原样进模型 system prompt。
        return "查询隐患记录台账。参数：hazardId（数字，可选，精确查询单条记录）；"
             + "status（可选，pending_confirm=待确认 或 confirmed=已确认）；"
             + "locationKeyword（可选，部位关键词，如 3号楼）。参数可组合，都不传时返回最近 5 条。"
             + "返回每条记录的 id、分类、部位、描述、整改要求、依据、状态。";
    }

    @Override
    public String execute(String argsJson) {
        JsonNode args = parse(argsJson);
        if (args == null) {
            return "{\"error\":\"参数解析失败，需要 JSON 对象\",\"rawArgs\":" + jsonStr(argsJson) + "}";
        }

        // hazardId 精确查询优先：这是"追问某条记录"的主路径
        if (args.hasNonNull("hazardId")) {
            HazardDraft one = recordsMapper.findHazardById(args.path("hazardId").asLong());
            if (one == null) {
                return "{\"results\":[],\"hint\":\"该 id 的隐患记录不存在\"}";
            }
            log.info("[HazardDraftQuery] hazardId={} 命中", one.getId());
            return "{\"results\":[" + toJson(one) + "]}";
        }

        String status = args.path("status").asText(null);
        if (status != null && !status.isBlank()
                && !"pending_confirm".equals(status) && !"confirmed".equals(status)) {
            return "{\"error\":\"status 只支持 pending_confirm 或 confirmed\"}";
        }
        String kw = args.path("locationKeyword").asText(null);
        List<HazardDraft> hits = recordsMapper.searchHazards(
                (status == null || status.isBlank()) ? null : status,
                (kw == null || kw.isBlank()) ? null : kw.trim(), LIMIT);
        log.info("[HazardDraftQuery] status={} kw={} 命中 {} 条", status, kw, hits.size());
        if (hits.isEmpty()) {
            return "{\"results\":[],\"hint\":\"没有符合条件的隐患记录\"}";
        }
        return "{\"results\":[" + hits.stream().map(this::toJson).collect(Collectors.joining(",")) + "]}";
    }

    private String toJson(HazardDraft h) {
        return "{\"id\":" + h.getId()
                + ",\"category\":" + jsonStr(h.getCategory())
                + ",\"location\":" + jsonStr(h.getLocation())
                + ",\"description\":" + jsonStr(h.getDescription())
                + ",\"rectification\":" + jsonStr(h.getRectification())
                + ",\"basis\":" + jsonStr(h.getBasis())
                + ",\"status\":" + jsonStr(h.getStatus()) + "}";
    }

    private JsonNode parse(String argsJson) {
        try {
            JsonNode node = om.readTree(argsJson);
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 手工 JSON 转义：回喂模型的字符串宁可丑、不能非法 */
    private String jsonStr(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}