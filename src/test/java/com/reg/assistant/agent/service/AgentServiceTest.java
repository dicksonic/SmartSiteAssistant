package com.reg.assistant.agent.service;

import com.dicksonic.gaios.GaiosBootstrap;
import com.dicksonic.gaios.llm.LLMCore;
import com.dicksonic.gaios.security.ToolRegistry;
import com.dicksonic.gaios.tools.Tool;
import com.reg.assistant.auth.TokenService;
import com.reg.assistant.common.ApiException;
import com.reg.assistant.mapper.CallLogMapper;
import com.reg.assistant.mapper.RecordsMapper;
import com.reg.assistant.model.Entities.AssistantSession;
import com.reg.assistant.service.AssistantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AgentService（M1-3b）单测：用 mock LLMCore 驱动真实 SimpleReactAgent，
 * 覆盖 正常应答 / 轮数耗尽降级 / 异常降级 / 超时降级 / 降级开关 / 总开关 六条路径。
 * token 计量累加（UsageListener→ThreadLocal）需真实 DeepSeekCore 实例，单测不覆盖，
 * 由 M1-3c 真机 curl 验证（验证点：call_logs agent 行 usage_amount 非 0）。
 */
class AgentServiceTest {

    private LLMCore llm;
    private ToolRegistry registry;
    private TokenService tokenService;
    private AssistantService assistantService;
    private CallLogMapper callLogMapper;
    private RecordsMapper recordsMapper;

    private final Tool fakeTool = new Tool() {
        @Override
        public String name() {
            return "query_regulations";
        }

        @Override
        public String description() {
            return "查询建设工程质量安全相关规范条文";
        }

        @Override
        public String execute(String argsJson) {
            return "{\"items\":[]}";
        }
    };

    @BeforeEach
    void setUp() {
        llm = mock(LLMCore.class);
        registry = mock(ToolRegistry.class);
        tokenService = mock(TokenService.class);
        assistantService = mock(AssistantService.class);
        callLogMapper = mock(CallLogMapper.class);
        recordsMapper = mock(RecordsMapper.class);
        when(registry.approvedTools()).thenReturn(List.of(fakeTool));
        AssistantSession session = mock(AssistantSession.class);
        when(session.getId()).thenReturn(1L);
        when(tokenService.requireSession("tk")).thenReturn(session);
    }

    private AgentService newService(boolean enabled, int maxTurns, long timeoutMs, boolean fallback) {
        GaiosBootstrap.Components components = new GaiosBootstrap.Components(null, llm, registry);
        AgentService svc = new AgentService(components, tokenService, assistantService,
                callLogMapper, recordsMapper, enabled, maxTurns, timeoutMs, fallback);
        svc.attachUsageListener(); // llm 是 mock，走"非 DeepSeekCore"告警分支，计量按 0 记
        return svc;
    }

    @Test
    void agent正常应答_走agent链路并写成功计量() throws Exception {
        when(llm.chat(any())).thenReturn("{\"action\":\"final\",\"answer\":\"脚手架作业层应满铺脚手板\"}");
        AgentService svc = newService(true, 4, 5000, true);

        Map<String, Object> r = svc.askAgent("tk", "脚手架有什么要求？");

        assertEquals("agent", r.get("mode"));
        assertEquals(false, r.get("degraded"));
        assertEquals("脚手架作业层应满铺脚手板", r.get("answer"));
        verify(callLogMapper).insert(eq(1L), eq("qa"), eq("agent"), eq("gaios"), eq("deepseek"),
                eq(0.0), eq("千tokens"), anyLong(), eq(true));
        verify(recordsMapper).insertQa(eq(1L), eq("脚手架有什么要求？"), contains("脚手架作业层应满铺脚手板"));
        verifyNoInteractions(assistantService);
    }

    @Test
    void 轮数耗尽哨兵_写失败行并降级普通模式() throws Exception {
        // 模型持续输出非 JSON，agent 耗尽 maxTurns 返回哨兵串
        when(llm.chat(any())).thenReturn("让我想想……");
        when(assistantService.ask(eq("tk"), anyString(), isNull()))
                .thenReturn(new LinkedHashMap<>(Map.of("answer", "普通模式答案")));
        AgentService svc = newService(true, 4, 10000, true);

        Map<String, Object> r = svc.askAgent("tk", "脚手架有什么要求？");

        assertEquals("normal-fallback", r.get("mode"));
        assertEquals(true, r.get("degraded"));
        assertEquals("普通模式答案", r.get("answer"));
        verify(callLogMapper).insert(eq(1L), eq("qa"), eq("agent"), eq("gaios"), eq("deepseek"),
                eq(0.0), eq("千tokens"), anyLong(), eq(false));
    }

    @Test
    void agent抛异常_写失败行并降级() throws Exception {
        // 复现 DeepSeekCore 缺 key 的场景：IllegalStateException 直接从 run 穿出
        when(llm.chat(any())).thenThrow(new IllegalStateException("缺少环境变量 DEEPSEEK_API_KEY"));
        when(assistantService.ask(eq("tk"), anyString(), isNull()))
                .thenReturn(new LinkedHashMap<>(Map.of("answer", "普通模式答案")));
        AgentService svc = newService(true, 4, 5000, true);

        Map<String, Object> r = svc.askAgent("tk", "脚手架有什么要求？");

        assertEquals("normal-fallback", r.get("mode"));
        assertTrue(String.valueOf(r.get("degradeReason")).contains("IllegalStateException"));
        verify(callLogMapper).insert(eq(1L), eq("qa"), eq("agent"), eq("gaios"), eq("deepseek"),
                eq(0.0), eq("千tokens"), anyLong(), eq(false));
    }

    @Test
    void 超时_取消并降级() throws Exception {
        when(llm.chat(any())).thenAnswer(inv -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "{\"action\":\"final\",\"answer\":\"迟到的答案\"}";
        });
        when(assistantService.ask(eq("tk"), anyString(), isNull()))
                .thenReturn(new LinkedHashMap<>(Map.of("answer", "普通模式答案")));
        AgentService svc = newService(true, 4, 200, true);

        Map<String, Object> r = svc.askAgent("tk", "脚手架有什么要求？");

        assertEquals("normal-fallback", r.get("mode"));
        assertTrue(String.valueOf(r.get("degradeReason")).contains("超时"));
        verify(callLogMapper).insert(eq(1L), eq("qa"), eq("agent"), eq("gaios"), eq("deepseek"),
                eq(0.0), eq("千tokens"), anyLong(), eq(false));
    }

    @Test
    void 降级开关关闭_抛异常不回落() {
        when(llm.chat(any())).thenReturn("不是JSON");
        AgentService svc = newService(true, 4, 5000, false);

        assertThrows(ApiException.class, () -> svc.askAgent("tk", "脚手架有什么要求？"));
        verifyNoInteractions(assistantService);
    }

    @Test
    void agent总开关关闭_直接拒绝() {
        AgentService svc = newService(false, 4, 5000, true);

        assertThrows(ApiException.class, () -> svc.askAgent("tk", "脚手架有什么要求？"));
        verifyNoInteractions(assistantService, callLogMapper);
    }
}
