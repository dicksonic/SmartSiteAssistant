package com.reg.assistant.provider;

import com.reg.assistant.mapper.CallLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 计量网关：所有模型调用统一经此入口——
 * 选择供应商（mock/http）、记录耗时/用量/成败到 call_logs（第四阶段预算修正的数据源）。
 * 计量失败不阻断业务流程。
 */
@Component
public class MeteredGateway {

    private static final Logger log = LoggerFactory.getLogger(MeteredGateway.class);

    private final MockProvider mockProvider;
    private final HttpProvider httpProvider;
    private final CallLogMapper callLogMapper;

    @Value("${model.mode:mock}")
    private String mode;

    public MeteredGateway(MockProvider mockProvider, HttpProvider httpProvider, CallLogMapper callLogMapper) {
        this.mockProvider = mockProvider;
        this.httpProvider = httpProvider;
        this.callLogMapper = callLogMapper;
    }

    public ModelProvider provider() {
        return "http".equalsIgnoreCase(mode) ? httpProvider : mockProvider;
    }

    /**
     * 计量执行：scenario ∈ qa/hazard/material/compare
     * call 为对 provider 的一次调用，返回的 CallResult 自带用量信息。
     */
    public <T> ModelProvider.CallResult<T> execute(String scenario, Long sessionId,
                                                   Supplier<ModelProvider.CallResult<T>> call,
                                                   String apiType, String unit) {
        ModelProvider p = provider();
        long started = System.currentTimeMillis();
        try {
            ModelProvider.CallResult<T> result = call.get();
            writeLog(scenario, sessionId, result.usage.apiType, result.usage.amount, result.usage.unit,
                    result.provider, result.model, System.currentTimeMillis() - started, true);
            return result;
        } catch (Exception e) {
            writeLog(scenario, sessionId, apiType, 0, unit, p.name(), "-",
                    System.currentTimeMillis() - started, false);
            throw e;
        }
    }

    private void writeLog(String scenario, Long sessionId, String apiType, double amount, String unit,
                          String provider, String model, long latencyMs, boolean success) {
        try {
            callLogMapper.insert(sessionId, scenario, apiType, provider, model, amount, unit, latencyMs, success);
        } catch (Exception e) {
            log.error("[call_logs] 写入失败: {}", e.getMessage());
        }
    }
}
