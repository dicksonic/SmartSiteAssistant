package com.reg.assistant.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reg.assistant.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 真实API供应商（OpenAI兼容协议）。
 * 支持双供应商配置（A/B对比）：
 * - 文本走 model.base-url / api-key / text-name
 * - 视觉默认同文本；配置 model.vision-base-url / vision-api-key 后可走另一家（如文本DeepSeek+视觉通义VL）
 * 语音ASR/TTS协议因厂商而异（讯飞WebSocket/阿里一句话识别），按采购厂商在对应方法扩展。
 */
@Component
public class HttpProvider implements ModelProvider {

    @Value("${model.base-url:}")
    private String baseUrl;
    @Value("${model.api-key:}")
    private String apiKey;
    @Value("${model.text-name:kimi-k2-0905-preview}")
    private String textModel;
    @Value("${model.vision-base-url:}")
    private String visionBaseUrl;
    @Value("${model.vision-api-key:}")
    private String visionApiKey;
    @Value("${model.vision-name:moonshot-v1-32k-vision-preview}")
    private String visionModel;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper om = new ObjectMapper();

    @Override
    public String name() {
        return "http";
    }

    /** 视觉供应商地址/密钥：未单独配置时回落到文本供应商 */
    private String visionBaseUrl() {
        return visionBaseUrl == null || visionBaseUrl.isBlank() ? baseUrl : visionBaseUrl;
    }

    private String visionApiKey() {
        return visionApiKey == null || visionApiKey.isBlank() ? apiKey : visionApiKey;
    }

    private JsonNode chatCompletion(List<Map<String, Object>> messages, String model, String url, String key) {
        ApiException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String body = om.writeValueAsString(Map.<String, Object>of("model", model, "messages", messages, "temperature", 0.3));
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url + "/chat/completions"))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + key)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) throw new ApiException(502, "模型API错误 " + res.statusCode() + ": " + res.body());
                return om.readTree(res.body());
            } catch (ApiException e) {
                last = e;
            } catch (Exception e) {
                last = new ApiException(502, "模型API调用失败: " + e.getMessage());
            }
            try {
                Thread.sleep(1000L * (attempt + 1)); // 简单退避
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        throw last;
    }

    @Override
    public CallResult<String> chat(String system, String prompt, List<String> context) {
        var messages = new java.util.ArrayList<Map<String, Object>>();
        if (system != null && !system.isBlank()) messages.add(Map.<String, Object>of("role", "system", "content", system));
        if (context != null && !context.isEmpty()) {
            messages.add(Map.<String, Object>of("role", "system", "content", "可参考的规范条文：\n" + String.join("\n", context)));
        }
        messages.add(Map.<String, Object>of("role", "user", "content", prompt));

        JsonNode res = chatCompletion(messages, textModel,baseUrl, apiKey);
        String content = res.path("choices").path(0).path("message").path("content").asText("");
        double tokens = res.path("usage").path("total_tokens").asDouble(0) / 1000.0;
        return new CallResult<>(content, new Usage("text", tokens, "千tokens"), name(), textModel);
    }

    @Override
    public CallResult<String> vision(String imageBase64, String prompt) {
        var content = List.<Map<String, Object>>of(
                Map.<String, Object>of("type", "image_url", "image_url", Map.<String, Object>of("url", "data:image/jpeg;base64," + imageBase64)),
                Map.<String, Object>of("type", "text", "text", prompt));
        JsonNode res = chatCompletion(List.<Map<String, Object>>of(Map.<String, Object>of("role", "user", "content", content)), visionModel, visionBaseUrl(), visionApiKey());
        String text = res.path("choices").path(0).path("message").path("content").asText("");
        return new CallResult<>(text, new Usage("vision", 1, "次"), name(), visionModel);
    }

    @Override
    public CallResult<String> asr(String audioBase64) {
        // 按采购厂商协议扩展（讯飞WebSocket / 阿里一句话识别等）
        throw new ApiException(501, "真实ASR供应商尚未接入：请在 HttpProvider.asr 按厂商协议扩展");
    }

    @Override
    public CallResult<String> tts(String text) {
        // 按采购厂商协议扩展；未接入时前端自动降级为浏览器播报
        throw new ApiException(501, "真实TTS供应商尚未接入：请在 HttpProvider.tts 按厂商协议扩展");
    }
}
