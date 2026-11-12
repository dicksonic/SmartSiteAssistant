package com.reg.assistant.notice;

import com.dicksonic.gaios.llm.LLMCore;

/**
 * 通知单填充专用文本核心持有者（M2-4）：永不为 null 的 bean，内部可能为空。
 * 为什么独立实例而不复用 GaiosBootstrap 共享的 DeepSeekCore：
 * setUsageListener 是单槽语义，AgentService 已挂在共享实例上，
 * 通知单再挂会顶掉 Agent 计量——两个调用方各持实例，互不干扰。
 */
public class TextCoreHolder {

    private final LLMCore core;

    private TextCoreHolder(LLMCore core) {
        this.core = core;
    }

    public static TextCoreHolder of(LLMCore core) {
        return new TextCoreHolder(core);
    }

    public static TextCoreHolder unavailable() {
        return new TextCoreHolder(null);
    }

    public boolean available() {
        return core != null;
    }

    /** 调用前先 available()；不可用返回 null */
    public LLMCore core() {
        return core;
    }
}
