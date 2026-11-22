package com.reg.assistant.report;

import com.dicksonic.gaios.llm.LLMCore;

/**
 * 报告长文生成专用文本核心持有者（M3-4）：永不为 null 的 bean，内部可能为空。
 * 为什么又开一个独立实例而不复用 notice 的 TextCoreHolder / bootstrap 共享 DeepSeekCore：
 * setUsageListener 是单槽语义——AgentService 占着共享实例、NoticeService 占着 TextCoreHolder，
 * 报告生成再挂任何一边都会顶掉对方的计量。三个调用方各持实例，互不干扰。
 */
public class ReportCoreHolder {

    private final LLMCore core;

    private ReportCoreHolder(LLMCore core) {
        this.core = core;
    }

    public static ReportCoreHolder of(LLMCore core) {
        return new ReportCoreHolder(core);
    }

    public static ReportCoreHolder unavailable() {
        return new ReportCoreHolder(null);
    }

    public boolean available() {
        return core != null;
    }

    /** 调用前先 available()；不可用返回 null */
    public LLMCore core() {
        return core;
    }
}
