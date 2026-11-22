package com.reg.assistant.report;

import com.dicksonic.gaios.llm.LLMCore;
import com.dicksonic.gaios.llm.Message;
import com.reg.assistant.auth.TokenService;
import com.reg.assistant.common.ApiException;
import com.reg.assistant.mapper.CallLogMapper;
import com.reg.assistant.mapper.ReportMapper;
import com.reg.assistant.model.Entities.AssistantSession;
import com.reg.assistant.report.provider.ReportRuleRegistry;
import com.reg.assistant.report.service.ReportGenerateService;
import com.reg.assistant.report.service.ReportStatusMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M3-4 长文生成单测：mock ReportMapper/CallLogMapper/TokenService + 真实 ReportStatusMachine/ReportRuleRegistry，
 * LLM 用顺序应答桩（实现 LLMCore 契约，无需 Mockito）。覆盖：
 * 正常四节+洞察落库与 refs 域过滤 / 重新生成清场幂等 / LLM 中途失败转 failed 不留半截 /
 * 小预算强制触发 D6 压缩 / 无数据块拒绝 / confirmed 状态机拒绝 / 核心降级提示 / 洞察 JSON 降级。
 * SQL 与真实 LLM 由真机验收，单测不覆盖。
 */
class ReportGenerateServiceTest {

    /** 顺序应答桩：每次 chat 弹出下一个应答，并记录每轮实际收到的消息（供压缩断言） */
    static class StubLlmCore implements LLMCore {
        final Queue<String> answers = new ArrayDeque<>();
        final List<List<Message>> seen = new ArrayList<>();

        StubLlmCore(String... answers) {
            this.answers.addAll(List.of(answers));
        }

        @Override
        public String chat(List<Message> messages) {
            seen.add(messages);
            return answers.isEmpty() ? "桩尾默认应答" : answers.poll();
        }
    }

    private TokenService tokenService;
    private ReportMapper reportMapper;
    private CallLogMapper callLogMapper;

    @BeforeEach
    void setUp() {
        tokenService = mock(TokenService.class);
        reportMapper = mock(ReportMapper.class);
        callLogMapper = mock(CallLogMapper.class);
        AssistantSession session = mock(AssistantSession.class);
        when(session.getId()).thenReturn(7L);
        when(session.getUserName()).thenReturn("tester");
        when(tokenService.requireSession(anyString())).thenReturn(session);
        when(reportMapper.findInstanceById(9L)).thenReturn(instance("preview"));
        when(reportMapper.listDataBlocksByInstance(9L)).thenReturn(List.of(
                block("M001", "HAZARD_TOTAL", "hash-h"),
                block("M002", "CALL_TOTAL", "hash-p")));
        when(reportMapper.transitionInstance(anyLong(), anyString(), anyString())).thenReturn(1);
    }

    private ReportGenerateService service(LLMCore llm, int maxTokens, int keepRecent) {
        return new ReportGenerateService(ReportCoreHolder.of(llm), tokenService, reportMapper,
                new ReportStatusMachine(), new ReportRuleRegistry(), callLogMapper, maxTokens, keepRecent);
    }

    private Map<String, Object> instance(String status) {
        Map<String, Object> inst = new HashMap<>();
        inst.put("id", 9L);
        inst.put("templateId", 1L);
        inst.put("status", status);
        inst.put("paramsJson", "{\"timeFrom\":\"2026-11-01\",\"timeTo\":\"2026-11-30\",\"orgScope\":\" Suzhou\"}");
        return inst;
    }

    private Map<String, Object> block(String metricCode, String ruleCode, String hash) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("instanceId", 9L);
        b.put("blockId", metricCode);
        b.put("metricCode", metricCode);
        b.put("columnsJson", "[\"name\",\"value\"]");
        b.put("rowsJson", "[[\"甲\",1],[\"乙\",2],[\"丙\",3],[\"丁\",4]]");
        b.put("metaJson", "{\"ruleCode\":\"" + ruleCode + "\",\"ruleName\":\"" + ruleCode
                + "规则\",\"ruleVersion\":\"1.0\",\"orgScope\":\"default\",\"timeRange\":\"all\"}");
        b.put("hash", hash);
        return b;
    }

    @Test
    void generateSuccess_fourSectionsInsightsAndDomainRefs() {
        StubLlmCore llm = new StubLlmCore("概览正文", "隐患正文", "平台正文", "结论正文",
                "[{\"text\":\"隐患确认率偏低\",\"severity\":\"warn\"},{\"text\":\"调用量稳定\",\"severity\":\"info\"}]");
        Map<String, Object> r = service(llm, 6000, 4).generate("tk", 9L);

        assertEquals(4, r.get("sections"));
        assertEquals(2, r.get("insights"));
        assertEquals(false, r.get("compressed"));
        assertEquals("preview", r.get("status"));

        // 四节按骨架顺序落库；refs 按域过滤：概览/结论引全部块，隐患节只引隐患块，平台节只引平台块
        ArgumentCaptor<Map> sectionCaptor = ArgumentCaptor.forClass(Map.class);
        verify(reportMapper, times(4)).insertSection(sectionCaptor.capture());
        List<Map> rows = sectionCaptor.getAllValues();
        assertEquals("OVERVIEW", rows.get(0).get("sectionCode"));
        assertEquals(1, rows.get(0).get("seq"));
        assertEquals(4, rows.get(3).get("seq"));
        String overviewRefs = (String) rows.get(0).get("refsJson");
        assertTrue(overviewRefs.contains("hash-h") && overviewRefs.contains("hash-p"));
        String hazardRefs = (String) rows.get(1).get("refsJson");
        assertTrue(hazardRefs.contains("hash-h") && !hazardRefs.contains("hash-p"));
        String platformRefs = (String) rows.get(2).get("refsJson");
        assertTrue(platformRefs.contains("hash-p") && !platformRefs.contains("hash-h"));
        String conclusionRefs = (String) rows.get(3).get("refsJson");
        assertTrue(conclusionRefs.contains("hash-h") && conclusionRefs.contains("hash-p"));

        // 洞察两条、severity 原样落库
        ArgumentCaptor<Map> insightCaptor = ArgumentCaptor.forClass(Map.class);
        verify(reportMapper, times(2)).insertInsight(insightCaptor.capture());
        assertEquals("warn", insightCaptor.getAllValues().get(0).get("severity"));
        assertEquals("info", insightCaptor.getAllValues().get(1).get("severity"));
        assertEquals("summary", insightCaptor.getAllValues().get(0).get("insightType"));

        // 状态流转 preview→generating→preview；计量一行 success=true / scenario=report
        verify(reportMapper).transitionInstance(9L, "preview", "generating");
        verify(reportMapper).transitionInstance(9L, "generating", "preview");
        verify(callLogMapper).insert(eq(7L), eq("report"), eq("text"), eq("gaios"), eq("deepseek"),
                anyDouble(), eq("千tokens"), anyLong(), eq(true));
        // 桩不是 DeepSeekCore，无 UsageListener，usage 为 0
        assertEquals(Map.of("promptTokens", 0, "completionTokens", 0, "totalTokens", 0), r.get("usage"));
    }

    @Test
    void regenerate_clearsOldSectionsAndSummaryInsightsFirst() {
        StubLlmCore llm = new StubLlmCore("一", "二", "三", "四", "[]x");
        // "[]x" 不是合法 JSON？——是合法 JSON 数组前缀但带尾巴，解析失败走降级，仍落 1 条；本用例只关心清场次序
        service(llm, 6000, 4).generate("tk", 9L);

        InOrder order = inOrder(reportMapper);
        order.verify(reportMapper).deleteSectionsByInstance(9L);
        order.verify(reportMapper).deleteSummaryInsightsByInstance(9L);
        order.verify(reportMapper, times(4)).insertSection(any());
    }

    @Test
    void llmFailureMidway_marksFailedAndLeavesNothing() {
        StubLlmCore llm = new StubLlmCore("第一节正文", "error");
        ReportGenerateService s = service(llm, 6000, 4);

        ApiException e = assertThrows(ApiException.class, () -> s.generate("tk", 9L));
        assertTrue(e.getMessage().contains("章节生成失败"));

        // 先全生成后落库：第二节失败，一节都不落
        verify(reportMapper, never()).insertSection(any());
        verify(reportMapper, never()).insertInsight(any());
        // 状态 generating→failed 且 error 落列；计量 success=false
        ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
        verify(reportMapper).transitionInstanceWithError(eq(9L), eq("generating"), eq("failed"), err.capture());
        assertTrue(err.getValue().contains("章节生成失败"));
        verify(callLogMapper).insert(any(), anyString(), anyString(), anyString(), anyString(),
                anyDouble(), anyString(), anyLong(), eq(false));
    }

    @Test
    void tinyBudget_triggersD6Compression() {
        String longText = "长".repeat(150);
        StubLlmCore llm = new StubLlmCore(longText, longText, longText, longText,
                "[{\"text\":\"要点一\",\"severity\":\"info\"}]");
        // 预算 200 / 阈值 160：系统提示+事实包即超阈值，第 3 节前后必然触发滑出压缩
        Map<String, Object> r = service(llm, 200, 2).generate("tk", 9L);

        assertEquals(true, r.get("compressed"));
        assertTrue((Integer) r.get("coveredMessages") > 0);
        // 4 节 + 1 洞察轮 + 至少 1 次 LlmSummarizer 压缩调用
        assertTrue(llm.seen.size() > 5);
        // 压缩摘要挂在窗口里：后续某轮 snapshot 中应出现摘要形态的 system 消息（含"用户目标"/"摘要"字样）
        boolean sawSummary = llm.seen.stream().flatMap(List::stream)
                .anyMatch(m -> "system".equals(m.role()) && m.content().contains("摘要"));
        assertTrue(sawSummary);
    }

    @Test
    void noBlocks_rejected() {
        when(reportMapper.listDataBlocksByInstance(9L)).thenReturn(List.of());
        StubLlmCore llm = new StubLlmCore();
        ReportGenerateService s = service(llm, 6000, 4);

        ApiException e = assertThrows(ApiException.class, () -> s.generate("tk", 9L));
        assertTrue(e.getMessage().contains("无数据块"));
        verify(reportMapper, never()).transitionInstance(anyLong(), anyString(), anyString());
        verifyNoInteractions(callLogMapper);
    }

    @Test
    void confirmedInstance_rejectedByStatusMachine() {
        when(reportMapper.findInstanceById(9L)).thenReturn(instance("confirmed"));
        StubLlmCore llm = new StubLlmCore();
        ReportGenerateService s = service(llm, 6000, 4);

        ApiException e = assertThrows(ApiException.class, () -> s.generate("tk", 9L));
        assertTrue(e.getMessage().contains("状态不允许流转"));
        verifyNoInteractions(callLogMapper);
    }

    @Test
    void coreUnavailable_degradesWithClearMessage() {
        ReportGenerateService s = new ReportGenerateService(ReportCoreHolder.unavailable(), tokenService,
                reportMapper, new ReportStatusMachine(), new ReportRuleRegistry(), callLogMapper, 6000, 4);

        ApiException e = assertThrows(ApiException.class, () -> s.generate("tk", 9L));
        assertTrue(e.getMessage().contains("未启用"));
        verifyNoInteractions(reportMapper, callLogMapper);
    }

    @Test
    void insightsMalformed_fallsBackToSingleRawInsight() {
        StubLlmCore llm = new StubLlmCore("一", "二", "三", "四", "（模型忘了输出 JSON）");
        Map<String, Object> r = service(llm, 6000, 4).generate("tk", 9L);

        assertEquals(1, r.get("insights"));
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(reportMapper, times(1)).insertInsight(captor.capture());
        assertEquals("（模型忘了输出 JSON）", captor.getValue().get("text"));
        assertEquals("info", captor.getValue().get("severity"));
        // 降级不打挂主链：状态照常回 preview
        assertEquals("preview", r.get("status"));
        assertFalse((Boolean) r.get("compressed"));
    }
}
