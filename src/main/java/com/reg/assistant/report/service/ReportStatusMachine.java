package com.reg.assistant.report.service;

import com.reg.assistant.common.ApiException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 报告实例状态机。
 * M3-3 起 start 同步完成真取数：draft/failed → generating → preview 在同一请求内顺序流转，
 * 每步守卫压 SQL（本地 SQL 毫秒级，用户拍板同步）；将来取数异步化（D8）时，
 * generating → preview 改由生成任务完成，不在请求线程同步跳转。
 */
@Component
public class ReportStatusMachine {

    public static final String DRAFT = "draft";
    public static final String GENERATING = "generating";
    public static final String PREVIEW = "preview";
    public static final String CONFIRMED = "confirmed";
    public static final String EXPORTED = "exported";
    public static final String REVISED = "revised";
    public static final String FAILED = "failed";

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            DRAFT, Set.of(GENERATING, FAILED),
            GENERATING, Set.of(PREVIEW, FAILED),
            PREVIEW, Set.of(CONFIRMED, GENERATING, FAILED),
            CONFIRMED, Set.of(EXPORTED, REVISED),
            EXPORTED, Set.of(REVISED),
            FAILED, Set.of(GENERATING)
    );

    public void assertTransition(String from, String to) {
        if (from == null || to == null || !ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new ApiException("报告状态不允许流转: " + from + " -> " + to);
        }
    }
}