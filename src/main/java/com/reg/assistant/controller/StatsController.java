package com.reg.assistant.controller;

import com.reg.assistant.mapper.CallLogMapper;
import com.reg.assistant.mapper.KnowledgeMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

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
        return callLogMapper.usageDaily();
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
