package com.reg.assistant.report;

import com.reg.assistant.auth.TokenService;
import com.reg.assistant.common.ApiException;
import com.reg.assistant.mapper.ReportMapper;
import com.reg.assistant.model.Entities.AssistantSession;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M3-2 候选确认入库单测：mock ReportMapper/TokenService，纯逻辑覆盖
 * 保存（code 生成/origin 映射/整体替换/改即回草稿）、校验拒绝、确认闸门（正常/幂等/无草稿）、废弃守卫。
 * SQL 与事务真实行为由真机验证（验收清单 V1-V6），单测不覆盖。
 */
class ReportMetricServiceTest {

    private TokenService tokenService;
    private ReportMapper reportMapper;
    private ReportService service;

    @BeforeEach
    void setUp() {
        tokenService = mock(TokenService.class);
        reportMapper = mock(ReportMapper.class);
        AssistantSession session = mock(AssistantSession.class);
        when(session.getUserName()).thenReturn("tester");
        when(tokenService.requireSession(anyString())).thenReturn(session);
        service = new ReportService(tokenService, reportMapper, new ReportStatusMachine(),
                mock(ReportTemplateParser.class), "data/report-uploads", 20,
                mock(ReportCandidateExtractor.class));
    }

    private Map<String, Object> template(String status) {
        Map<String, Object> t = new HashMap<>();
        t.put("id", 1L);
        t.put("status", status);
        t.put("version", 1);
        return t;
    }

    private Map<String, Object> candidate(String code, String name, String source) {
        Map<String, Object> c = new LinkedHashMap<>();
        if (code != null) c.put("metricCode", code);
        c.put("metricName", name);
        c.put("unit", "%");
        c.put("dimension", "区市");
        c.put("section", "总体情况");
        c.put("suggestedChart", "bar");
        c.put("source", source);
        return c;
    }

    /** 正常路径：无 code 行自动生成 M%03d；paragraph/table→extracted、manual→custom；删旧+插新+回退钩子各被调用 */
    @Test
    void saveMetrics生成编码并映射origin() {
        when(reportMapper.findTemplateById(1L)).thenReturn(template("draft"));
        when(reportMapper.maxMetricCode(1L)).thenReturn(null);

        Map<String, Object> r = service.saveMetrics("tk", 1L, List.of(
                candidate(null, "汇聚率", "paragraph"),   // 无 code → 服务端生成 M001，origin=extracted
                candidate(null, "自定义指标", "manual"))); // manual → origin=custom

        assertEquals(2, r.get("saved"));
        ArgumentCaptor<Map> cap = ArgumentCaptor.forClass(Map.class);
        verify(reportMapper, times(2)).insertMetric(cap.capture());
        Map<String, Object> first = cap.getAllValues().get(0);
        assertEquals("M001", first.get("metricCode"));
        assertEquals("extracted", first.get("origin"));
        assertEquals("draft", first.get("status"));
        assertTrue(((String) first.get("specJson")).contains("总体情况"));
        assertEquals("custom", cap.getAllValues().get(1).get("origin"));
        verify(reportMapper).deleteEditableMetricsByTemplate(1L);
        verify(reportMapper).reopenTemplateOnEdit(1L); // draft 时 0 行无副作用，但调用语义显式
    }

    /** 序号接续（M009→M010）；请求体内编码重复→400 且不落库 */
    @Test
    void saveMetrics序号接续与编码查重() {
        when(reportMapper.findTemplateById(1L)).thenReturn(template("draft"));
        when(reportMapper.maxMetricCode(1L)).thenReturn("M009"); // 接续 M010
        service.saveMetrics("tk", 1L, List.of(candidate(null, "指标A", "table")));
        ArgumentCaptor<Map> cap = ArgumentCaptor.forClass(Map.class);
        verify(reportMapper).insertMetric(cap.capture());
        assertEquals("M010", cap.getValue().get("metricCode"));

        // 请求体内编码重复 → 拒绝，不落库
        ApiException e = assertThrows(ApiException.class, () -> service.saveMetrics("tk", 1L,
                List.of(candidate("X1", "指标B", "table"), candidate("X1", "指标C", "table"))));
        assertTrue(e.getMessage().contains("重复"));
    }

    /** 校验三连：空列表/缺名称/非法图型→均 400，且无任何 insert */
    @Test
    void saveMetrics非法入参拒绝() {
        when(reportMapper.findTemplateById(1L)).thenReturn(template("draft"));
        when(reportMapper.maxMetricCode(1L)).thenReturn(null);
        assertThrows(ApiException.class, () -> service.saveMetrics("tk", 1L, List.of())); // 空列表
        Map<String, Object> noName = candidate(null, "", "table");
        assertThrows(ApiException.class, () -> service.saveMetrics("tk", 1L, List.of(noName))); // 缺名称
        Map<String, Object> badChart = candidate(null, "指标D", "table");
        badChart.put("suggestedChart", "radar");
        assertThrows(ApiException.class, () -> service.saveMetrics("tk", 1L, List.of(badChart))); // 非法图型
        verify(reportMapper, never()).insertMetric(any());
    }

    /** locked 模板拒绝编辑；confirmed 模板允许保存但模板显式退回 draft（"改即回草稿"） */
    @Test
    void saveMetrics锁定模板拒绝且改已确认模板触发回退() {
        when(reportMapper.findTemplateById(1L)).thenReturn(template("locked"));
        assertThrows(ApiException.class, () -> service.saveMetrics("tk", 1L, List.of(candidate(null, "指标E", "table"))));
        verify(reportMapper, never()).insertMetric(any());

        // confirmed 模板允许编辑，但模板显式退回 draft（"改即回草稿"）
        when(reportMapper.findTemplateById(1L)).thenReturn(template("confirmed"));
        when(reportMapper.maxMetricCode(1L)).thenReturn(null);
        Map<String, Object> r = service.saveMetrics("tk", 1L, List.of(candidate(null, "指标F", "table")));
        assertEquals("draft", r.get("templateStatus"));
        verify(reportMapper).reopenTemplateOnEdit(1L);
    }

    /** 确认闸门：3 条草稿转 confirmed + 模板 draft→confirmed */
    @Test
    void confirmMetrics正常流转() {
        when(reportMapper.findTemplateById(1L)).thenReturn(template("draft"));
        when(reportMapper.confirmDraftMetrics(1L)).thenReturn(3);
        when(reportMapper.confirmTemplate(1L)).thenReturn(1);
        Map<String, Object> r = service.confirmMetrics("tk", 1L);
        assertEquals(3, r.get("confirmed"));
        assertEquals("confirmed", r.get("templateStatus"));
    }

    /** 幂等（已 confirmed 直接返回 0 不碰指标行）；draft 但无草稿行→400 提示先保存 */
    @Test
    void confirmMetrics幂等与无草稿拒绝() {
        // 模板已 confirmed：重复确认幂等返回，不触碰指标行
        when(reportMapper.findTemplateById(1L)).thenReturn(template("confirmed"));
        Map<String, Object> r = service.confirmMetrics("tk", 1L);
        assertEquals(0, r.get("confirmed"));
        verify(reportMapper, never()).confirmDraftMetrics(anyLong());

        // draft 模板但无草稿行：提示先保存
        when(reportMapper.findTemplateById(1L)).thenReturn(template("draft"));
        when(reportMapper.confirmDraftMetrics(1L)).thenReturn(0);
        ApiException e = assertThrows(ApiException.class, () -> service.confirmMetrics("tk", 1L));
        assertTrue(e.getMessage().contains("没有待确认"));
    }

    /** confirmed→deprecated 正常；指标不存在或非 confirmed→400 */
    @Test
    void deprecateMetric守卫() {
        when(reportMapper.deprecateMetric(9L)).thenReturn(1);
        assertEquals("deprecated", service.deprecateMetric("tk", 9L).get("status"));

        when(reportMapper.deprecateMetric(8L)).thenReturn(0); // 非 confirmed 或不存在
        assertThrows(ApiException.class, () -> service.deprecateMetric("tk", 8L));
    }
}
