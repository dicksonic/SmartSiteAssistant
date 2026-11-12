package com.reg.assistant.controller;

import com.reg.assistant.agent.service.AgentService;
import com.reg.assistant.hazard.service.HazardRecognizeService;
import com.reg.assistant.notice.service.NoticeService;
import com.reg.assistant.service.AssistantService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 四大应用场景接口 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService assistantService;

    private final AgentService agentService;

    private final HazardRecognizeService hazardRecognizeService;

    private final NoticeService noticeService;

    public AssistantController(AssistantService assistantService, AgentService agentService,
                               HazardRecognizeService hazardRecognizeService, NoticeService noticeService) {
        this.assistantService = assistantService;
        this.agentService = agentService;
        this.hazardRecognizeService = hazardRecognizeService;
        this.noticeService = noticeService;
    }

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody Map<String, Object> body) throws Exception {
        return assistantService.ask(
                (String) body.get("token"),
                (String) body.get("question"),
                (String) body.get("audioBase64"));
    }

    /** 场景① Agent 模式：G-AIOS ReAct 链路，与 /ask 平行，供前端同题对比 */
    @PostMapping("/askAgent")
    public Map<String, Object> askAgent(@RequestBody Map<String, Object> body) throws Exception {
        return agentService.askAgent(
                (String) body.get("token"),
                (String) body.get("question"));
    }

    @PostMapping("/describeHazard")
    public Map<String, Object> describeHazard(@RequestBody Map<String, Object> body) throws Exception {
        return assistantService.describeHazard(
                (String) body.get("token"),
                (String) body.get("imageBase64"),
                (String) body.get("text"),
                (String) body.get("audioBase64"),
                (String) body.get("location"));
    }

    @PostMapping("/reviewMaterial")
    @SuppressWarnings("unchecked")
    public Map<String, Object> reviewMaterial(@RequestBody Map<String, Object> body) throws Exception {
        return assistantService.reviewMaterial(
                (String) body.get("token"),
                (String) body.get("materialName"),
                (String) body.get("docType"),
                (List<Map<String, Object>>) body.get("pages"));
    }

    @PostMapping("/compareRectification")
    public Map<String, Object> compareRectification(@RequestBody Map<String, Object> body) throws Exception {
        return assistantService.compareRectification(
                (String) body.get("token"),
                (String) body.get("beforeText"),
                (String) body.get("afterText"),
                (String) body.get("beforeImageBase64"),
                (String) body.get("afterImageBase64"));
    }

    @PostMapping("/synthesize")
    public Map<String, Object> synthesize(@RequestBody Map<String, Object> body) {
        return assistantService.synthesize(
                (String) body.get("token"),
                (String) body.get("text"));
    }

    /** 隐患识别（M2-2）：样本图片清单 */
    @GetMapping("/hazardImages")
    public Object hazardImages(@RequestParam String token) {
        return hazardRecognizeService.listImages(token);
    }

    /** 隐患识别（M2-2）：预览图字节流（前端缩略图），复用 fetcher 的全部校验 */
    @GetMapping("/hazardImage")
    public ResponseEntity<byte[]> hazardImage(@RequestParam String token, @RequestParam String name) throws Exception {
        Path p = hazardRecognizeService.imagePath(token, name);
        String n = p.getFileName().toString().toLowerCase();
        MediaType type = n.endsWith(".png") ? MediaType.IMAGE_PNG
                : n.endsWith(".webp") ? MediaType.valueOf("image/webp") : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).body(Files.readAllBytes(p));
    }

    /** 隐患识别（M2-2）：识别主链，返回待确认草稿 */
    @PostMapping("/recognizeHazard")
    public Map<String, Object> recognizeHazard(@RequestBody Map<String, Object> body) {
        return hazardRecognizeService.recognize(
                (String) body.get("token"),
                (String) body.get("imageName"),
                (String) body.get("location"));
    }

    /** 隐患识别（M2-2）：人工确认入库（可携带修正字段） */
    @PostMapping("/confirmHazard")
    public Map<String, Object> confirmHazard(@RequestBody Map<String, Object> body) {
        return hazardRecognizeService.confirm(
                (String) body.get("token"),
                ((Number) body.get("hazardId")).longValue(),
                (String) body.get("category"),
                (String) body.get("location"),
                (String) body.get("description"),
                (String) body.get("rectification"),
                (String) body.get("basis"));
    }

    /** 场景② 追问闭环（M2-3）：就某条隐患记录问 Agent（查条文/拟通知单/查台账） */
    @PostMapping("/askHazardAgent")
    public Map<String, Object> askHazardAgent(@RequestBody Map<String, Object> body) {
        return agentService.askHazardAgent(
                (String) body.get("token"),
                ((Number) body.get("hazardId")).longValue(),
                (String) body.get("question"));
    }

    // ---- 通知单模板化（M2-4）----

    /** 模板清单（含槽位集合）：本地目录模拟平台模板配置模块 */
    @GetMapping("/noticeTemplates")
    public Object noticeTemplates(@RequestParam String token) {
        return noticeService.listTemplates(token);
    }

    /** 填充：LLM 只产槽位 JSON（真实计量 scenario=hazard api_type=text provider=gaios） */
    @PostMapping("/fillNotice")
    public Map<String, Object> fillNotice(@RequestBody Map<String, Object> body) {
        return noticeService.fill(
                (String) body.get("token"),
                ((Number) body.get("hazardId")).longValue(),
                (String) body.get("templateName"));
    }

    /** 渲染：poi-tl 确定性替换，零 LLM 不计量，返回 docx 字节流下载 */
    @PostMapping("/renderNotice")
    @SuppressWarnings("unchecked")
    public ResponseEntity<byte[]> renderNotice(@RequestBody Map<String, Object> body) {
        NoticeService.RenderedDoc doc = noticeService.render(
                (String) body.get("token"),
                (String) body.get("templateName"),
                (Map<String, String>) body.get("values"));
        String encoded = URLEncoder.encode(doc.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(doc.content());
    }

    /** 办公人员上传模板：四防线（穿越/扩展名/大小/同名覆盖）在 provider 内 */
    @PostMapping("/uploadNoticeTemplate")
    public Map<String, Object> uploadNoticeTemplate(@RequestParam String token,
                                                    @RequestParam("file") MultipartFile file) throws Exception {
        return noticeService.upload(token, file.getOriginalFilename(), file.getBytes());
    }
}
