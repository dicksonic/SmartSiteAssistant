package com.reg.assistant.config;

import com.dicksonic.gaios.GaiosBootstrap;
import com.dicksonic.gaios.llm.vision.impl.QwenVLCore;
import com.dicksonic.gaios.util.AppConfigUtil;
import com.reg.assistant.agent.HazardDraftTool;
import com.reg.assistant.agent.RegulationSearchTool;
import com.reg.assistant.agent.service.KnowledgeSearchService;
import com.reg.assistant.hazard.VisionCoreHolder;
import com.reg.assistant.mapper.KnowledgeMapper;
import com.reg.assistant.mapper.RecordsMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * G-AIOS 运行时接入：启动时装配一次，全应用共享。
 * 共享安全依据：E5 引擎 / LLM 客户端 / ToolRegistry 均无对话状态；
 * 每请求的对话状态由调用方 createAgent() 新建（Context 随 run() 创建、toolResultCache 随实例创建）。
 */
@Configuration
public class GaiosConfig {

    private static final Logger log = LogManager.getLogger(GaiosConfig.class);

    /** E5 模型目录：绝对路径，开发期直接指 G-AIOS 工程的 models，部署时随包带 */
    @Value("${gaios.models-dir}")
    private String modelsDir;

    /**
     * 装配运行时（不带内置 mock 工具），并注册 web 侧真实工具。
     * 真实工具只能在这里注册一次：注册即触发三阶段安检（含 LLM 仲裁，有真实调用成本），
     * 绝不可放进请求链路——那是每问一次烧一次钱。
     */
    @Bean
    public GaiosBootstrap.Components gaiosComponents(KnowledgeSearchService searchService, RecordsMapper recordsMapper) {
        try{
            GaiosBootstrap.Components c = GaiosBootstrap.boot(Path.of(modelsDir), false);
            c.registry().register(new RegulationSearchTool(searchService));
            c.registry().register(new HazardDraftTool(recordsMapper));
            log.info("[GaiosConfig] web 工具注册完成，隔离区: {}", c.registry().quarantined());
            return c;
        }
        catch (Exception ex){
            // fail-fast：G-AIOS 是核心依赖，装配上不来宁可启动失败。
            // 此前 return null 会让 Spring 把错误推迟到请求期（NullBean 注入失败/500），
            // 错误现场与真实原因隔了一层，违背高标准地基原则。
            throw new IllegalStateException("[GaiosConfig] G-AIOS 运行时装配失败，应用拒绝启动", ex);
        }
    }

    /**
     * 视觉核心条件装配：缺 vision.api.key 时只降级隐患识别一个功能，应用照常启动。
     * 与 gaiosComponents fail-fast 的策略差异是有意的：G-AIOS 核心是全应用地基，
     * 起不来宁可不启动；视觉只是单场景增强，不该绑架启动。
     */
    @Bean
    public VisionCoreHolder visionCoreHolder() {
        String key = AppConfigUtil.getOrDefault("vision.api.key", "");
        if (key.isBlank()) {
            log.warn("[GaiosConfig] 未配置 vision.api.key，隐患图像识别功能降级不可用，其余功能不受影响");
            return VisionCoreHolder.unavailable();
        }
        log.info("[GaiosConfig] VisionCore 装配完成（QwenVL / DashScope）");
        return VisionCoreHolder.of(new QwenVLCore());
    }

}