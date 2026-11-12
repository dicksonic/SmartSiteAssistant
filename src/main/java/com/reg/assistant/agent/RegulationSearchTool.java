package com.reg.assistant.agent;

import com.dicksonic.gaios.tools.Tool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.reg.assistant.agent.service.KnowledgeSearchService;
import com.reg.assistant.model.Entities.KnowledgeItem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 规范条文检索工具（真实库版）：G-AIOS 内置 RegulationQueryTool(mock) 的生产替代。
 * 经 ToolRegistry 三阶段安检后注册进 Agent 工具集，模型以 query_regulations 名字发起调用。
 * 检索当前走 knowledge_items 关键词 LIKE；M1.5 升级 E5 向量检索时只改本类 execute 一处。
 */
public class RegulationSearchTool implements Tool {

    private static final Logger log = LogManager.getLogger(RegulationSearchTool.class);

    /**
     * 单次返回上限。取值依据：现种子库仅 6 条，5 条足够覆盖单主题；
     * 上限同时约束回喂模型的上下文体积。失效条件：条文库扩容后若模型报"没找到相关条文"，先检查是否被此上限截断。
     */
    private static final int LIMIT = 5;
    private final ObjectMapper om = new ObjectMapper();

    private final KnowledgeSearchService searchService;

    public RegulationSearchTool(KnowledgeSearchService searchService) {
        this.searchService = searchService;
    }


    @Override
    public String name() { return "query_regulations"; }

    @Override
    public String description() {
        // 警告（同 G-AIOS Tool 接口约定）：这段文字原样进模型 system prompt。
        // "必须引用出处、禁止编造" 写在这里，是防幻觉的关键约束，不能省。
        return "查询建设工程质量安全规范条文库。参数：keyword（2~4个字的检索词，如 基坑、脚手架、临时用电、高处作业、塔吊）。"
             + "返回带规范名称和条款号的条文，回答用户时必须引用这些出处，禁止编造规范。";
    }

    @Override
    public String execute(String argsJson) {
        String keyword = parseKeyword(argsJson);
        if (keyword == null) {
            // 错误路径：原文回喂，让模型自我修正（ReAct 循环的容错设计）
            return "{\"error\":\"参数解析失败，需要含 keyword 字段的 JSON 对象\",\"rawArgs\":" + jsonStr(argsJson) + "}";
        }
        List<KnowledgeItem> hits = searchService.search(keyword, LIMIT);
        log.info("[RegulationSearch] keyword={} 命中 {} 条", keyword, hits.size());
        if (hits.isEmpty()) {
            return "{\"results\":[],\"hint\":\"未命中，请换更贴近现场用语的问法重试，如 基坑、脚手架、临时用电、高处作业、塔吊\"}";
        }
        String results = hits.stream()
                .map(i -> "{\"title\":" + jsonStr(i.getTitle())
                        + ",\"category\":" + jsonStr(i.getCategory())
                        + ",\"content\":" + jsonStr(i.getContent()) + "}")
                .collect(Collectors.joining(","));
        return "{\"results\":[" + results + "]}";
    }

    private String parseKeyword(String argsJson) {
        try {
            JsonNode node = om.readTree(argsJson);
            String kw = node.path("keyword").asText(null);
            return (kw == null || kw.isBlank()) ? null : kw.trim();
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