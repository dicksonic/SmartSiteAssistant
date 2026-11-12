package com.reg.assistant.config;

import com.dicksonic.gaios.GaiosBootstrap;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * G-AIOS 运行时健康探针：挂进 /actuator/health 的 components.gaios 节点。
 * 判定逻辑：注册表里有工具被隔离 = 安检拦下了东西、服务能力受损 → DOWN，运维应介入查隔离原因。
 * （E5 模型与 LLM 客户端在 boot 时已验证可用，此处探的是运行期可变状态：工具注册表。）
 */
@Component("gaios")
public class GaiosHealthIndicator implements HealthIndicator {

    private final GaiosBootstrap.Components components;

    public GaiosHealthIndicator(GaiosBootstrap.Components components) {
        this.components = components;
    }

    @Override
    public Health health() {
        var quarantined = components.registry().quarantined();
        Health.Builder builder = quarantined.isEmpty() ? Health.up() : Health.down();
        return builder
                .withDetail("approvedTools", components.registry().approvedTools().size())
                .withDetail("quarantined", quarantined)
                .build();
    }
}