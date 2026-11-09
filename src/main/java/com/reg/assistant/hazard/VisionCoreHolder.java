package com.reg.assistant.hazard;

import com.dicksonic.gaios.llm.vision.VisionCore;

/**
 * VisionCore 条件装配的持有者：永不为 null 的 bean，内部可能为空。
 * 为什么不能 @Bean 返回 null：Spring 会注册成 NullBean，注入点炸在请求期，
 * 错误现场与真实原因隔一层（GaiosConfig 注释里记录过的教训）。
 */
public class VisionCoreHolder {

    private final VisionCore core;

    private VisionCoreHolder(VisionCore core) {
        this.core = core;
    }

    public static VisionCoreHolder of(VisionCore core) {
        return new VisionCoreHolder(core);
    }

    public static VisionCoreHolder unavailable() {
        return new VisionCoreHolder(null);
    }

    public boolean available() {
        return core != null;
    }

    /** 调用前先 available()；不可用返回 null */
    public VisionCore core() {
        return core;
    }
}