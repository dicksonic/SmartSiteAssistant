package com.reg.assistant.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reg.assistant.agent.service.KnowledgeSearchService;
import com.reg.assistant.mapper.KnowledgeMapper;
import com.reg.assistant.model.Entities.KnowledgeItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RegulationSearchTool 契约测试。
 * 背景：工具输出直接回喂模型，非法 JSON 会让 ReAct 循环当场翻车；
 * description 是防幻觉的安全字段，被误删约束等于拆防线——这些都用测试锁死。
 */
class RegulationSearchToolTest {

    private final ObjectMapper om = new ObjectMapper();

    /** 手写桩：测试只关心 searchByKeywords 的返回，其余方法空实现 */
    private KnowledgeSearchService stub(List<KnowledgeItem> hits) {
        return new KnowledgeSearchService(null, null) {
            @Override public List<KnowledgeItem> search(String query, int limit) { return hits; }
        };
    }

    private KnowledgeItem item(String title, String content) {
        KnowledgeItem i = new KnowledgeItem();
        i.setCategory("基坑工程");
        i.setTitle(title);
        i.setContent(content);
        return i;
    }

    @Test
    void 工具名必须保持稳定_模型按此名发起调用() {
        assertEquals("query_regulations", new RegulationSearchTool(stub(List.of())).name());
    }

    @Test
    void description必须含引用出处约束_这是防幻觉的安全字段() {
        // MCPTox 威胁模型下 description 既是攻击面也是防线：误删"引用出处/禁止编造"=拆防线
        String desc = new RegulationSearchTool(stub(List.of())).description();
        assertTrue(desc.contains("出处") && desc.contains("禁止编造"),
                "description 丢失防幻觉约束，当前内容：" + desc);
    }

    @Test
    void 命中时返回合法JSON且特殊字符已转义() throws Exception {
        // content 塞引号和换行：手工转义若漏一处，模型收到的就是非法 JSON
        RegulationSearchTool tool = new RegulationSearchTool(stub(List.of(
                item("GB50497-2019 基坑监测", "支护结构内力\"报警值\"应满足：\n每日巡查不少于1次"))));
        JsonNode root = om.readTree(tool.execute("{\"keyword\":\"基坑\"}"));
        JsonNode results = root.path("results");
        assertEquals(1, results.size());
        assertEquals("GB50497-2019 基坑监测", results.get(0).path("title").asText());
        assertTrue(results.get(0).path("content").asText().contains("报警值"));
    }

    @Test
    void 未命中时返回空结果和换词提示() throws Exception {
        JsonNode root = om.readTree(new RegulationSearchTool(stub(List.of())).execute("{\"keyword\":\"盾构\"}"));
        assertEquals(0, root.path("results").size());
        assertTrue(root.path("hint").asText().contains("重试"),
                "未命中时应引导模型换词重试");
    }

    @Test
    void 参数非法时回喂原文_支撑ReAct自我修正() throws Exception {
        JsonNode root = om.readTree(new RegulationSearchTool(stub(List.of())).execute("这不是JSON"));
        assertTrue(root.path("error").asText().contains("keyword"));
        assertEquals("这不是JSON", root.path("rawArgs").asText(),
                "错误路径必须原样回喂入参，模型才能自我修正");
    }

    @Test
    void 缺keyword字段视为参数非法() throws Exception {
        JsonNode root = om.readTree(new RegulationSearchTool(stub(List.of())).execute("{\"kw\":\"基坑\"}"));
        assertTrue(root.has("error"));
    }
}