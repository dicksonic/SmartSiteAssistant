package com.reg.assistant.report;

import com.reg.assistant.common.ApiException;
import com.reg.assistant.mapper.ReportDataMapper;
import com.reg.assistant.report.provider.LocalDbReportDataProvider;
import com.reg.assistant.report.provider.ReportDataProvider;
import com.reg.assistant.report.provider.ReportRuleRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M3-3 本地取数 Provider 单测：mock ReportDataMapper + 真实 ReportRuleRegistry，覆盖
 * 数据块组装（列/行/meta 契约纪律字段）、hash 稳定性与敏感性、未知规则与版本漂移 fail-closed、
 * 比率 Java 侧计算、时间参数解析（右开区间 +1 天）、maxRows 透传、非法日期拒绝。
 * SQL 真实行为由真机验证（验收清单 V3/V7），单测不覆盖。
 */
class LocalDbReportDataProviderTest {

    private ReportDataMapper dataMapper;
    private LocalDbReportDataProvider provider;

    @BeforeEach
    void setUp() {
        dataMapper = mock(ReportDataMapper.class);
        provider = new LocalDbReportDataProvider(dataMapper, new ReportRuleRegistry());
    }

    private ReportDataProvider.DataQuery query(String metric, String rule, Map<String, Object> params) {
        return new ReportDataProvider.DataQuery(metric, rule, "1.0", params, "default", "all", 500, 5000);
    }

    @Test
    void 单值规则组装数据块含契约纪律字段() {
        when(dataMapper.countHazards(null, null)).thenReturn(12L);
        ReportDataProvider.DataBlock b = provider.query(query("M001", "HAZARD_TOTAL", Map.of()));
        assertEquals("M001", b.blockId());
        assertEquals("M001", b.metricCode());
        assertEquals(List.of("hazard_count"), b.columns());
        assertEquals(12L, b.rows().get(0).get(0));
        assertEquals(64, b.hash().length());
        // 契约纪律：orgScope/timeRange/ruleVersion 必须进 meta，任何实现不得绕开数据范围
        assertEquals("default", b.meta().get("orgScope"));
        assertEquals("all", b.meta().get("timeRange"));
        assertEquals("1.0", b.meta().get("ruleVersion"));
        assertEquals("local-db", b.meta().get("provider"));
    }

    @Test
    void 未知规则failClosed且不触碰数据库() {
        ApiException e = assertThrows(ApiException.class,
                () -> provider.query(query("M001", "DROP_TABLE", Map.of())));
        assertTrue(e.getMessage().contains("未知取数规则"));
        verifyNoInteractions(dataMapper);
    }

    @Test
    void 规则版本漂移拒绝取数() {
        ReportDataProvider.DataQuery stale = new ReportDataProvider.DataQuery(
                "M001", "HAZARD_TOTAL", "0.9", Map.of(), "default", "all", 500, 5000);
        ApiException e = assertThrows(ApiException.class, () -> provider.query(stale));
        assertTrue(e.getMessage().contains("不一致"));
        verifyNoInteractions(dataMapper);
    }

    @Test
    void hash稳定且对内容敏感() {
        when(dataMapper.countHazards(null, null)).thenReturn(12L);
        String h1 = provider.query(query("M001", "HAZARD_TOTAL", Map.of())).hash();
        // 同数据同指纹（hash 不含 metricCode，支撑跨指标/跨实例比对）
        String h2 = provider.query(query("M002", "HAZARD_TOTAL", Map.of())).hash();
        assertEquals(h1, h2);
        when(dataMapper.countHazards(null, null)).thenReturn(13L);
        String h3 = provider.query(query("M001", "HAZARD_TOTAL", Map.of())).hash();
        assertNotEquals(h1, h3);
    }

    @Test
    void 确认率除法在Java侧计算且分母零不炸() {
        when(dataMapper.countConfirmedHazards(null, null)).thenReturn(3L);
        when(dataMapper.countHazards(null, null)).thenReturn(10L);
        ReportDataProvider.DataBlock b = provider.query(query("M001", "HAZARD_CONFIRM_RATE", Map.of()));
        assertEquals(List.of("confirmed_count", "total_count", "confirm_rate"), b.columns());
        assertEquals(0.3, ((Number) b.rows().get(0).get(2)).doubleValue());

        when(dataMapper.countConfirmedHazards(null, null)).thenReturn(0L);
        when(dataMapper.countHazards(null, null)).thenReturn(0L);
        ReportDataProvider.DataBlock empty = provider.query(query("M001", "HAZARD_CONFIRM_RATE", Map.of()));
        assertEquals(0.0, ((Number) empty.rows().get(0).get(2)).doubleValue());
    }

    @Test
    void 时间范围解析为右开区间() {
        when(dataMapper.countHazards(any(), any())).thenReturn(1L);
        provider.query(query("M001", "HAZARD_TOTAL",
                Map.of("timeFrom", "2026-01-01", "timeTo", "2026-03-31")));
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> toEx = ArgumentCaptor.forClass(LocalDate.class);
        verify(dataMapper).countHazards(from.capture(), toEx.capture());
        assertEquals(LocalDate.of(2026, 1, 1), from.getValue());
        assertEquals(LocalDate.of(2026, 4, 1), toEx.getValue()); // timeTo + 1 天，右开区间
    }

    @Test
    void 多行规则透传maxRows并组装键值行() {
        when(dataMapper.groupHazardsByCategory(isNull(), isNull(), anyInt()))
                .thenReturn(List.of(Map.of("category", "基坑工程", "cnt", 5L)));
        ReportDataProvider.DataBlock b = provider.query(new ReportDataProvider.DataQuery(
                "M001", "HAZARD_BY_CATEGORY", "1.0", Map.of(), "default", "all", 1, 5000));
        verify(dataMapper).groupHazardsByCategory(null, null, 1);
        assertEquals(List.of("category", "hazard_count"), b.columns());
        assertEquals(1, b.rows().size());
        assertEquals("基坑工程", b.rows().get(0).get(0));
        assertEquals(5L, b.rows().get(0).get(1));
    }

    @Test
    void 无数据时SUM折零() {
        when(dataMapper.sumCallUsage(null, null)).thenReturn(null);
        ReportDataProvider.DataBlock b = provider.query(query("M001", "TOKEN_USAGE_TOTAL", Map.of()));
        assertEquals(0.0, ((Number) b.rows().get(0).get(0)).doubleValue());
    }

    @Test
    void 非法日期参数拒绝() {
        ApiException e = assertThrows(ApiException.class,
                () -> provider.query(query("M001", "HAZARD_TOTAL", Map.of("timeFrom", "2026/01/01"))));
        assertTrue(e.getMessage().contains("yyyy-MM-dd"));
    }
}
