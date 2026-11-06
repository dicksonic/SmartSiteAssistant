package com.reg.assistant.provider;

import java.util.List;

/** 统一模型API封装接口：文本 / 视觉 / 语音ASR / 语音TTS */
public interface ModelProvider {

    String name();

    CallResult<String> chat(String system, String prompt, List<String> context);

    CallResult<String> vision(String imageBase64, String prompt);

    CallResult<String> asr(String audioBase64);

    /** 返回音频base64；mock 或未接入时返回 null，前端降级浏览器播报 */
    CallResult<String> tts(String text);

    /** 单次调用计量结果（用量按计费单位：千tokens / 次 / 秒 / 字符） */
    class Usage {
        public final String apiType; // text / vision / asr / tts
        public final double amount;
        public final String unit;

        public Usage(String apiType, double amount, String unit) {
            this.apiType = apiType;
            this.amount = amount;
            this.unit = unit;
        }
    }

    class CallResult<T> {
        public final T data;
        public final Usage usage;
        public final String provider;
        public final String model;

        public CallResult(T data, Usage usage, String provider, String model) {
            this.data = data;
            this.usage = usage;
            this.provider = provider;
            this.model = model;
        }
    }
}
