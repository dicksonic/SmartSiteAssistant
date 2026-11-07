package com.reg.assistant.controller;

import com.reg.assistant.mapper.CallLogMapper;
import com.reg.assistant.mapper.KnowledgeMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 数据看板与知识库 */
@RestController
@RequestMapping("/api")
public class StatsController {

    private final CallLogMapper callLogMapper;
    private final KnowledgeMapper knowledgeMapper;

    public StatsController(CallLogMapper callLogMapper, KnowledgeMapper knowledgeMapper) {
        this.callLogMapper = callLogMapper;
        this.knowledgeMapper = knowledgeMapper;
    }

    @GetMapping("/stats/usageSummary")
    public List<Map<String, Object>> usageSummary() {
        return callLogMapper.usageSummary();
    }

    @GetMapping("/stats/usageDaily")
    public List<Map<String, Object>> usageDaily() {
        // 返回结构与前端看板约定不变：day / callCount / totalUsage
        // TreeMap 按 key 天然升序，替代原 SQL 的 ORDER BY day
        Map<LocalDate, long[]> counts = new TreeMap<>();
        Map<LocalDate, double[]> usage = new TreeMap<>();
        for (Map<String, Object> row : callLogMapper.findDailySource()) {
            LocalDate day = ((Timestamp) row.get("createdAt")).toLocalDateTime().toLocalDate();
            counts.computeIfAbsent(day, k -> new long[1])[0]++;
            usage.computeIfAbsent(day, k -> new double[1])[0] += ((Number) row.get("usageAmount")).doubleValue();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        counts.forEach((day, c) -> result.add(Map.of(
                "day", day.toString(), "callCount", c[0], "totalUsage", usage.get(day)[0])));
        return result;
    }

    @GetMapping("/knowledge/list")
    public Object knowledgeList() {
        return knowledgeMapper.findAll(100);
    }

    @GetMapping("/knowledge/search")
    public Object knowledgeSearch(@RequestParam String keyword) {
        return knowledgeMapper.search(keyword);
    }
}
