package com.reg.assistant.agent.service;

import com.dicksonic.gaios.GaiosBootstrap;
import com.dicksonic.gaios.llm.impl.DeepSeekCore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reg.assistant.auth.TokenService;
import com.reg.assistant.common.ApiException;
import com.reg.assistant.mapper.CallLogMapper;
import com.reg.assistant.mapper.RecordsMapper;
import com.reg.assistant.model.Entities.*;
import com.reg.assistant.model.Entities.AssistantSession;
import com.reg.assistant.service.AssistantService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Agent 模式问答服务（M1-3b）：G-AIOS ReAct Agent 链路，与既有单发模式
 * （AssistantService.ask）平行，供前端同题对比。
 *
 * 设计要点：
 * 1. 每请求 createAgent(maxTurns)：Context 与工具结果缓存均为实例级，防串话；
 * 2. 失败降级：agent 失败（耗尽轮数/异常/超时）回落普通模式，用户无感；
 *    agent 尝试行照常写 call_logs（success=false），与普通模式行共同构成审计链；
 * 3. 真实计量：DeepSeekCore.UsageListener 旁路按请求累加 token（含 Context 摘要
 *    等隐藏调用），一次请求写一行 call_logs，unit 与 text 行一致（千tokens），
 *    usageSummary/usageDaily 统计口径不变；
 * 4. 注意：本链路恒定走真实 DeepSeek，不受 model.mode=mock 影响
 *    （G-AIOS 内核读自己的 app.properties）。mock 模式下同题对比即"mock vs 真实"，
 *    这是特性；若要纯 mock 演示请配 gaios.agent.enabled=false。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /**
     * 与 SimpleReactAgent.run 轮数耗尽返回串的哨兵约定（前缀匹配）。
     * 耦合提示：这是字符串级契约，内核改返回文案此处即失效；阶段2 内核加固应把
     * run 返回值结构化（AgentResult），届时此处同步删除。
     */
    private static final String AGENT_FAIL_SENTINEL = "超过最大轮数";

    // call_logs 行标识：scenario 沿用 qa（同场景），apiType 区分链路，
    // usageSummary 按 apiType+scenario 分组，自然拆出 text vs agent 对比行
    private static final String SCENARIO = "qa";
    private static final String SCENARIO_HAZARD = "hazard";
    private static final String API_TYPE = "agent";
    private static final String PROVIDER = "gaios";
    /** 模型名内核未暴露（DeepSeekCore 无 getter），先写常量；阶段2 内核加 getModelName() 后替换 */
    private static final String MODEL = "deepseek";

    // agent run 是阻塞 IO，必须离开 Tomcat 请求线程才能施加总时预算。
    // 取值依据：demo/试运行单机并发个位数，8 线程 + 50 队列足够；
    // 失效条件：量产按真实并发实测调整，或随 gaios.agent.* 一起外置为配置
    private static final int WORKER_CORE = 2;
    private static final int WORKER_MAX = 8;
    private static final int WORKER_QUEUE = 50;

    private final GaiosBootstrap.Components components;
    private final TokenService tokenService;
    private final AssistantService assistantService;
    private final CallLogMapper callLogMapper;
    private final RecordsMapper recordsMapper;

    private final boolean agentEnabled;
    private final int maxTurns;
    private final long totalTimeoutMs;
    private final boolean fallbackEnabled;

    private final ObjectMapper om = new ObjectMapper();

    /**
     * 按请求累加 token 的旁路：挂在共享 DeepSeekCore 单例上，用 ThreadLocal 区分请求。
     * 成立前提：agent run 全程单线程执行（本类用 worker 线程池保证）；
     * UsageListener 回调与 run 同线程（DeepSeekCore.fireUsage 在调用线程内触发）。
     */
    private final ThreadLocal<int[]> usageAcc = new ThreadLocal<>();

    private final ExecutorService agentExecutor;

    public AgentService(GaiosBootstrap.Components components,
                        TokenService tokenService,
                        AssistantService assistantService,
                        CallLogMapper callLogMapper,
                        RecordsMapper recordsMapper,
                        @Value("${gaios.agent.enabled:true}") boolean agentEnabled,
                        // 取值依据：SimpleReactAgent 构造注释（web 问答典型路径=1 工具+1 收口，4 留重试冗余）
                        @Value("${gaios.agent.max-turns:4}") int maxTurns,
                        // 取值依据：DeepSeek 单轮 P95<5s，4 轮+退避重试冗余约 30s，60s 留一倍余量；
                        // 失效条件：maxTurns 上调或 M2 多工具链式调用时同步上调
                        @Value("${gaios.agent.total-timeout-ms:60000}") long totalTimeoutMs,
                        @Value("${gaios.agent.fallback-enabled:true}") boolean fallbackEnabled) {
        this.components = components;
        this.tokenService = tokenService;
        this.assistantService = assistantService;
        this.callLogMapper = callLogMapper;
        this.recordsMapper = recordsMapper;
        this.agentEnabled = agentEnabled;
        this.maxTurns = maxTurns;
        this.totalTimeoutMs = totalTimeoutMs;
        this.fallbackEnabled = fallbackEnabled;
        // AbortPolicy：队列满即拒绝并走降级，绝不让 agent 任务无限排队拖垮请求
        this.agentExecutor = new ThreadPoolExecutor(WORKER_CORE, WORKER_MAX,
                60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(WORKER_QUEUE),
                r -> {
                    Thread t = new Thread(r, "gaios-agent-worker");
                    t.setDaemon(true); // 不挡 JVM 退出
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** 挂计量旁路：启动一次，之后所有请求的 token 经 ThreadLocal 归集 */
    @PostConstruct
    void attachUsageListener() {
        if (components.llm() instanceof DeepSeekCore core) {
            core.setUsageListener((prompt, completion, total) -> {
                int[] acc = usageAcc.get();
                if (acc != null) {
                    acc[0] += prompt;
                    acc[1] += completion;
                    acc[2] += total;
                }
                // acc==null：非请求链路触发的调用（如启动期安检仲裁），web 不重复计量
            });
        } else {
            log.warn("[AgentService] LLMCore 实现非 DeepSeekCore，agent 计量缺位（将按 0 记）");
        }
    }

    @PreDestroy
    void shutdown() {
        agentExecutor.shutdownNow();
    }

    /**
     * Agent 模式问答。
     * 成功：返回与 ask 同形状的 Map（answer/checklist/references/ttsText），
     *       附加 mode/degraded/agentLatencyMs/usage 诊断字段；
     * 失败：写 success=false 计量行后回落 AssistantService.ask（fallback-enabled 时）。
     */
    public Map<String, Object> askAgent(String token, String question) throws Exception {
        if (!agentEnabled) {
            throw new ApiException("Agent 模式未启用");
        }
        AssistantSession session = tokenService.requireSession(token);
        if (question == null || question.isBlank()) {
            throw new ApiException("请提供问题文本");
        }

        long start = System.currentTimeMillis();
        AgentOutcome outcome = runWithBudget(question);
        long latencyMs = System.currentTimeMillis() - start;

        if (outcome.error() == null && !isAgentFailure(outcome.answer())) {
            logCall(session.getId(), outcome, latencyMs, true,SCENARIO);
            // 与普通模式同形状留痕（answer/checklist/references），历史记录接口无需区分链路
            ObjectNode rec = om.createObjectNode();
            rec.put("answer", outcome.answer());
            rec.putArray("checklist");
            rec.putArray("references");
            recordsMapper.insertQa(session.getId(), question, om.writeValueAsString(rec));
            return successResult(question, outcome, latencyMs);
        }

        String reason = outcome.error() != null ? outcome.error()
                : "agent 未产出有效答案: " + outcome.answer();
        log.warn("[AgentService] agent 链路失败，原因：{}，耗时 {}ms，tokens={}",
                reason, latencyMs, outcome.totalTokens());
        logCall(session.getId(), outcome, latencyMs, false,SCENARIO);

        if (!fallbackEnabled) {
            throw new ApiException("Agent 链路失败：" + reason);
        }
        // 降级：普通模式内部经 MeteredGateway 自记一行 call_logs，与上面的 agent 失败行成对
        Map<String, Object> fallback = assistantService.ask(token, question, null);
        fallback.put("mode", "normal-fallback");
        fallback.put("degraded", true);
        fallback.put("degradeReason", reason);
        fallback.put("agentLatencyMs", latencyMs);
        fallback.put("agentUsage", usageMap(outcome));
        return fallback;
    }

    /**
     * 场景② 追问闭环（M2-3）：带着隐患记录上下文进 Agent 循环。
     * 与 askAgent 的差异：
     * 1. 当前草稿由主链确定性注入 prompt——已知信息不值得烧一轮工具调用去取；
     *    HazardDraftTool 留给"当前草稿之外"的检索（其他隐患/待确认清单）；
     * 2. 不做降级兜底：ask 的降级是为了保 QA 同题对比演示不断流，
     *    追问是新链路，诚实报错比静默换链路的"答案"更符合审计原则；
     * 3. scenario=hazard 计量，与 QA 的 agent 行分列。
     */
    public Map<String, Object> askHazardAgent(String token, long hazardId, String question) {
        if (!agentEnabled) {
            throw new ApiException("Agent 模式未启用");
        }
        AssistantSession session = tokenService.requireSession(token);
        if (question == null || question.isBlank()) {
            throw new ApiException("请提供问题文本");
        }
        HazardDraft draft = recordsMapper.findHazardById(hazardId);
        if (draft == null) {
            throw new ApiException("隐患记录不存在: " + hazardId);
        }

        String enriched = "【隐患记录#" + draft.getId() + "（" + statusLabel(draft.getStatus()) + "）】\n"
                + "分类：" + draft.getCategory()
                + "\n部位：" + dash(draft.getLocation())
                + "\n描述：" + draft.getDescription()
                + "\n整改要求：" + dash(draft.getRectification())
                + "\n依据：" + dash(draft.getBasis())
                + "\n\n【安全员追问】" + question;

        long start = System.currentTimeMillis();
        AgentOutcome outcome = runWithBudget(enriched);
        long latencyMs = System.currentTimeMillis() - start;

        boolean success = outcome.error() == null && !isAgentFailure(outcome.answer());
        logCall(session.getId(), outcome, latencyMs, success, SCENARIO_HAZARD);
        if (!success) {
            String reason = outcome.error() != null ? outcome.error() : "agent 未产出有效答案";
            log.warn("[AgentService] 隐患追问失败 hazardId={}，原因：{}，耗时 {}ms", hazardId, reason, latencyMs);
            throw new ApiException("Agent 追问失败：" + reason);
        }

        // 追问留痕进 qa_records（带隐患锚点前缀），历史记录接口无需新增表
        recordsMapper.insertQa(session.getId(), "【隐患#" + hazardId + "】" + question, outcome.answer());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hazardId", hazardId);
        result.put("hazardStatus", draft.getStatus());
        result.put("question", question);
        result.put("answer", outcome.answer());
        result.put("mode", "agent");
        result.put("agentLatencyMs", latencyMs);
        result.put("usage", usageMap(outcome));
        return result;
    }

    private static String statusLabel(String status) {
        return "confirmed".equals(status) ? "已确认" : "待确认";
    }

    private static String dash(String s) {
        return (s == null || s.isBlank()) ? "未填写" : s;
    }

    /** agent 单次执行结果：answer 与 error 二选一；usage 为本次请求累计真实 token */
    private record AgentOutcome(String answer, int promptTokens, int completionTokens,
                                int totalTokens, String error) {
    }

    private AgentOutcome runWithBudget(String question) {
        // acc 在任务外创建：超时后 worker 仍在跑时，请求线程也能读到已消耗的 token
        final int[] acc = new int[3];
        final Map<String, String> mdc = MDC.getCopyOfContextMap(); // traceId 带进 worker 线程
        final Future<AgentOutcome> future;
        try {
            future = agentExecutor.submit(() -> {
                if (mdc != null) {
                    MDC.setContextMap(mdc);
                }
                usageAcc.set(acc);
                try {
                    String answer = components.createAgent(maxTurns).run(question);
                    return new AgentOutcome(answer, acc[0], acc[1], acc[2], null);
                } catch (Exception e) {
                    // 含 DeepSeekCore 缺 key 的 IllegalStateException——它从 run 直接穿出
                    return new AgentOutcome(null, acc[0], acc[1], acc[2],
                            e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    usageAcc.remove();
                    MDC.clear();
                }
            });
        } catch (RejectedExecutionException e) {
            return new AgentOutcome(null, 0, 0, 0, "agent 工作池已满");
        }

        try {
            return future.get(totalTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            // 已知限制：cancel 只能中断当前这次 HTTP 调用，interrupt 被 DeepSeekCore 消费后
            // 循环仍可能把剩余轮数跑完（最多再烧 maxTurns-1 轮 token）。
            // 协作式取消需 SimpleReactAgent 支持，列入阶段2 内核加固。
            log.warn("[AgentService] agent 超时已取消(>{}ms)，worker 可能继续空转剩余轮数", totalTimeoutMs);
            return new AgentOutcome(null, acc[0], acc[1], acc[2], "agent 超时(>" + totalTimeoutMs + "ms)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return new AgentOutcome(null, acc[0], acc[1], acc[2], "请求线程被中断");
        } catch (ExecutionException e) {
            // 理论不可达（task 内部已兜底），防御性保留
            return new AgentOutcome(null, acc[0], acc[1], acc[2], "agent 执行异常: " + e.getCause());
        }
    }

    private boolean isAgentFailure(String answer) {
        return answer == null || answer.isBlank() || answer.startsWith(AGENT_FAIL_SENTINEL);
    }

    /** 计量写库失败不阻断主流程（同 MeteredGateway 原则：计量是旁路） */
    private void logCall(Long sessionId, AgentOutcome o, long latencyMs, boolean success, String scenario) {
        try {
            callLogMapper.insert(sessionId, scenario, API_TYPE, PROVIDER, MODEL,
                    o.totalTokens() / 1000.0, "千tokens", latencyMs, success);
        } catch (Exception e) {
            log.warn("[AgentService] call_logs 写入失败: {}", e.getMessage());
        }
    }

    private Map<String, Object> successResult(String question, AgentOutcome o, long latencyMs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);
        result.put("answer", o.answer());
        result.put("checklist", List.of());
        result.put("references", List.of());
        result.put("asrText", null);
        result.put("ttsText", o.answer());
        result.put("mode", "agent");
        result.put("degraded", false);
        result.put("agentLatencyMs", latencyMs);
        result.put("usage", usageMap(o));
        return result;
    }

    private Map<String, Object> usageMap(AgentOutcome o) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("promptTokens", o.promptTokens());
        u.put("completionTokens", o.completionTokens());
        u.put("totalTokens", o.totalTokens());
        return u;
    }
}
