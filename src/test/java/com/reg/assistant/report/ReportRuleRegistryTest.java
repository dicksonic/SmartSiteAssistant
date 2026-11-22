package com.reg.assistant.report;

import com.reg.assistant.common.ApiException;
import com.reg.assistant.report.provider.ReportRule;
import com.reg.assistant.report.provider.ReportRuleRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3-3 取数规则白名单注册表单测：规则数量与编码唯一性、find 命中/未命中、require fail-closed。
 */
class ReportRuleRegistryTest {

    @Test
    void 首批九条规则编码唯一且版本齐全() {
        ReportRuleRegistry registry = new ReportRuleRegistry();
        assertEquals(9, registry.list().size());
        assertEquals(9L, registry.list().stream().map(ReportRule::ruleCode).distinct().count());
        assertTrue(registry.list().stream().allMatch(r -> !r.version().isBlank()));
        assertTrue(registry.list().stream().allMatch(r -> !r.ruleCode().isBlank() && !r.name().isBlank()));
    }

    @Test
    void find命中与未命中() {
        ReportRuleRegistry registry = new ReportRuleRegistry();
        assertTrue(registry.find("HAZARD_TOTAL").isPresent());
        assertTrue(registry.find("CALL_BY_SCENARIO").isPresent());
        assertTrue(registry.find("DROP_TABLE").isEmpty());
    }

    @Test
    void require未知规则抛400并指向规则目录() {
        ReportRuleRegistry registry = new ReportRuleRegistry();
        ApiException e = assertThrows(ApiException.class, () -> registry.require("DELETE_ALL"));
        assertTrue(e.getMessage().contains("未知取数规则"));
        assertTrue(e.getMessage().contains("/api/report/rules"));
    }
}
