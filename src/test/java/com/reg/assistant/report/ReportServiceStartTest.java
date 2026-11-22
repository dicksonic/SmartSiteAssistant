package com.reg.assistant.report;

import com.reg.assistant.auth.TokenService;
import com.reg.assistant.common.ApiException;
import com.reg.assistant.mapper.ReportMapper;
import com.reg.assistant.model.Entities.AssistantSession;
import com.reg.assistant.report.provider.ReportDataProvider;
import com.reg.assistant.report.provider.ReportRuleRegistry;
import com.reg.assistant.report.service.ReportCandidateExtractor;
import com.reg.assistant.report.service.ReportService;
import com.reg.assistant.report.service.ReportStatusMachine;
import com.reg.assistant.report.service.ReportTemplateParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M3-3 start 真取数单测：mock ReportMapper/ReportDataProvider + 真实 ReportStatusMachine/ReportRuleRegistry，覆盖
 * 前置守卫（preview 幂等/无模板/模板未确认/无可用指标）、正常链路（落块/取数即锁/未绑定 missing/状态流转）、
 * 失败转 failed 落 error、failed 重试清场。SQL 与真取数由真机验证（验收清单 V1-V8），单测不覆盖。
 */
class ReportServiceStartTest {

    private TokenService tokenService;
    private ReportMapper reportMapper;
    private ReportDataProvider dataProvider;
    private ReportService service;

    @BeforeEach
    void setUp() {
        tokenService = mock(TokenService.class);
        reportMapper = mock(ReportMapper.class);
        dataProvider = mock(ReportDataProvider.class);
        AssistantSession session = mock(AssistantSession.class);
        when(session.getUserName()).thenReturn("tester");
        when(tokenService.requireSession(anyString())).thenReturn(session);
        service = new ReportService(tokenService, reportMapper, new ReportStatusMachine(),
                mock(ReportTemplateParser.class), "data/report-uploads", 20,
                mock(ReportCandidateExtractor.class), new ReportRuleRegistry(), dataProvider);
    }

    private Map<String, Object> instance(String status, Object templateId) {
        Map<String, Object> inst = new HashMap<>();
        inst.put("id", 9L);
        inst.put("templateId", templateId);
        inst.put("status", status);
        inst.put("paramsJson", "{}");
        return inst;
    }

    private Map<String, Object> confirmedTemplate() {
        Map<String, Object> t = new HashMap<>();
        t.put("id", 1L);
        t.put("status", "confirmed");
        t.put("version", 1);
        return t;
    }

    private Map<String, Object> metric(long id, String code, String ruleCode, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("metricCode", code);
        m.put("metricName", "指标" + code);
        m.put("ruleCode", ruleCode);
        m.put("ruleVersion", ruleCode == null ? null : "1.0");
        m.put("status", status);
        return m;
    }

    private ReportDataProvider.DataBlock block(String metricCode) {
        return new ReportDataProvider.DataBlock(metricCode, metricCode,
                List.of("hazard_count"), List.of(List.<Object>of(12L)),
                Map.of("provider", "local-db"), "a".repeat(64));
    }

    private void stubConfirmedTemplateWithMetrics(List<Map<String, Object>> metrics) {
        when(reportMapper.findTemplateById(1L)).thenReturn(confirmedTemplate());
        when(reportMapper.listUsableMetricsByTemplate(1L)).thenReturn(metrics);
    }

    @Test
    void preview状态重复start幂等返回且零写交互() {
        when(reportMapper.findInstanceById(9L)).thenReturn(instance("preview", 1L));
        Map<String, Object> result = service.start("tk", 9L);
        assertEquals("preview", result.get("status"));
        verify(reportMapper, never()).transitionInstance(anyLong(), anyString(), anyString());
        verifyNoInteractions(dataProvider);
    }

    @Test
    void 实例未关联模板拒绝取数() {
        when(reportMapper.findInstanceById(9L)).thenReturn(instance("draft", null));
        ApiException e = assertThrows(ApiException.class, () -> service.start("tk", 9L));
        assertTrue(e.getMessage().contains("未关联模板"));
        verifyNoInteractions(dataProvider);
    }

    @Test
    void 模板未走确认闸门拒绝取数() {
        when(reportMapper.findInstanceById(9L)).thenReturn(instance("draft", 1L));
        Map<String, Object> t = confirmedTemplate();
        t.put("status", "draft");
        when(reportMapper.findTemplateById(1L)).thenReturn(t);
        ApiException e = assertThrows(ApiException.class, () -> service.start("tk", 9L));
        assertTrue(e.getMessage().contains("确认闸门"));
        verifyNoInteractions(dataProvider);
    }

    @Test
    void 模板无可用指标拒绝取数() {
        when(reportMapper.findInstanceById(9L)).thenReturn(instance("draft", 1L));
        stubConfirmedTemplateWithMetrics(List.of());
        ApiException e = assertThrows(ApiException.class, () -> service.start("tk", 9L));
        assertTrue(e.getMessage().contains("无可用指标"));
        verifyNoInteractions(dataProvider);
    }

    @Test
    void 正常链路落块取数即锁未绑定落missing() {
        when(reportMapper.findInstanceById(9L))
                .thenReturn(instance("draft", 1L), instance("preview", 1L));
        stubConfirmedTemplateWithMetrics(List.of(
                metric(101L, "M001", "HAZARD_TOTAL", "confirmed"),
                metric(102L, "M002", null, "confirmed"),   // 未绑定规则：跳过 + missing insight
                metric(103L, "M003", "CALL_TOTAL", "locked"))); // 已锁定：可取数、不再重复锁
        when(dataProvider.query(any())).thenAnswer(inv -> {
            ReportDataProvider.DataQuery q = inv.getArgument(0);
            return block(q.metricCode());
        });
        when(reportMapper.transitionInstance(9L, "draft", "generating")).thenReturn(1);
        when(reportMapper.transitionInstance(9L, "generating", "preview")).thenReturn(1);
        when(reportMapper.listDataBlocksByInstance(9L)).thenReturn(List.of(Map.of(), Map.of()));

        Map<String, Object> result = service.start("tk", 9L);

        // 未绑定指标不取数；locked 指标正常取数
        verify(dataProvider, times(2)).query(any());
        verify(reportMapper, times(2)).insertDataBlock(any());
        // 未绑定指标落 missing insight
        ArgumentCaptor<Map<String, Object>> insight = ArgumentCaptor.forClass(Map.class);
        verify(reportMapper).insertInsight(insight.capture());
        assertEquals("missing", insight.getValue().get("insightType"));
        assertTrue(((String) insight.getValue().get("text")).contains("M002"));
        // 取数即锁：只锁本次取数成功的 confirmed 指标（M001）；M003 已 locked 不动
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(reportMapper).lockConfirmedMetricsByIds(ids.capture());
        assertEquals(List.of(101L), ids.getValue());
        // 返回体：preview + 块数 + 未绑定清单
        assertEquals("preview", result.get("status"));
        assertEquals(2, result.get("blocks"));
        assertTrue(((List<String>) result.get("unboundMetrics")).get(0).contains("M002"));
    }

    @Test
    void 取数异常实例转failed并落error() {
        when(reportMapper.findInstanceById(9L)).thenReturn(instance("draft", 1L));
        stubConfirmedTemplateWithMetrics(List.of(metric(101L, "M001", "HAZARD_TOTAL", "confirmed")));
        when(reportMapper.transitionInstance(9L, "draft", "generating")).thenReturn(1);
        when(dataProvider.query(any())).thenThrow(new ApiException("未知取数规则: X"));

        assertThrows(ApiException.class, () -> service.start("tk", 9L));
        verify(reportMapper).transitionInstanceWithError(eq(9L), eq("generating"), eq("failed"), contains("未知取数规则"));
        // 先取数后落库：provider 炸了不留半截数据块
        verify(reportMapper, never()).insertDataBlock(any());
    }

    @Test
    void failed实例重试先清场再落库() {
        when(reportMapper.findInstanceById(9L))
                .thenReturn(instance("failed", 1L), instance("preview", 1L));
        stubConfirmedTemplateWithMetrics(List.of(metric(103L, "M003", "CALL_TOTAL", "locked")));
        when(dataProvider.query(any())).thenAnswer(inv -> block("M003"));
        when(reportMapper.transitionInstance(9L, "failed", "generating")).thenReturn(1);
        when(reportMapper.transitionInstance(9L, "generating", "preview")).thenReturn(1);
        when(reportMapper.listDataBlocksByInstance(9L)).thenReturn(List.of(Map.of()));

        service.start("tk", 9L);

        verify(reportMapper).deleteDataBlocksByInstance(9L);
        verify(reportMapper).deleteMissingInsightsByInstance(9L);
        verify(reportMapper, times(1)).insertDataBlock(any());
    }

    @Test
    void 非法时间参数实例转failed并上抛400() {
        Map<String, Object> inst = instance("draft", 1L);
        inst.put("paramsJson", "{\"timeFrom\":\"2026-13-40\"}");
        when(reportMapper.findInstanceById(9L)).thenReturn(inst);
        stubConfirmedTemplateWithMetrics(List.of(metric(101L, "M001", "HAZARD_TOTAL", "confirmed")));
        when(reportMapper.transitionInstance(9L, "draft", "generating")).thenReturn(1);
        when(dataProvider.query(any())).thenAnswer(inv -> {
            // 模拟真实 LocalDbReportDataProvider 的日期解析失败
            throw new ApiException("取数参数 timeFrom 日期格式应为 yyyy-MM-dd: 2026-13-40");
        });

        ApiException e = assertThrows(ApiException.class, () -> service.start("tk", 9L));
        assertTrue(e.getMessage().contains("yyyy-MM-dd"));
        verify(reportMapper).transitionInstanceWithError(eq(9L), eq("generating"), eq("failed"), anyString());
    }
}
