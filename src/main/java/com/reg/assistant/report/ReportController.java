package com.reg.assistant.report;

import com.reg.assistant.report.service.ReportService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** M3 综合分析报告 */
@RestController
@RequestMapping("/api/report")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/templates")
    public Object templates(@RequestParam String token) {
        return reportService.listTemplates(token);
    }

    @GetMapping("/templates/{id}")
    public Object template(@RequestParam String token, @PathVariable long id) {
        return reportService.getTemplate(token, id);
    }

    @PostMapping("/instances")
    public Object createInstance(@RequestBody Map<String, Object> body) {
        return reportService.createInstance(
                (String) body.get("token"),
                body.get("templateId") == null ? null : ((Number) body.get("templateId")).longValue(),
                (Map<String, Object>) body.get("params"));
    }

    @PostMapping("/instances/{id}/start")
    public Object start(@RequestBody Map<String, Object> body, @PathVariable long id) {
        return reportService.start((String) body.get("token"), id);
    }

    @GetMapping("/instances/{id}")
    public Object instance(@RequestParam String token, @PathVariable long id) {
        return reportService.getInstance(token, id);
    }

    @PostMapping(value = "/templates/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object upload(@RequestParam String token, @RequestPart("file") MultipartFile file) throws Exception {
        return reportService.uploadTemplate(token, file.getOriginalFilename(), file.getSize(), file.getInputStream());
    }

    @GetMapping("/templates/{id}/raw")
    public Object raw(@RequestParam String token, @PathVariable long id) {
        return reportService.getTemplateRaw(token, id);
    }

    @PostMapping("/templates/{id}/extract")
    public Object extract(@RequestBody Map<String, Object> body, @PathVariable long id) {
        return reportService.reExtractCandidates((String) body.get("token"), id);
    }

    // ==================== M3-2 候选确认 → report_metrics 正式表 ====================
    // 交互时序：candidates（拉机器建议）→ PUT metrics（人工编辑后整体保存为草稿，可反复）
    //           → metrics/confirm（确认闸门，幂等）→ deprecate（已确认指标的单条废弃）

    /** 拉候选集：GET /api/report/templates/{id}/candidates?token=xx → {id, candidates}（只读 spec_json） */
    @GetMapping("/templates/{id}/candidates")
    public Object candidates(@RequestParam String token, @PathVariable long id) {
        return reportService.getCandidates(token, id);
    }

    /** 正式指标列表：GET /api/report/templates/{id}/metrics?token=xx → {id, templateStatus, metrics}（不含已废弃） */
    @GetMapping("/templates/{id}/metrics")
    public Object metrics(@RequestParam String token, @PathVariable long id) {
        return reportService.listMetrics(token, id);
    }

    /**
     * 保存指标集（整体替换，"保存即草稿"）：PUT /api/report/templates/{id}/metrics
     * body: {token, metrics:[{metricCode?, metricName*, unit, dimension, section, suggestedChart, source, ...}]}
     * → {id, saved, templateStatus:"draft"}；模板已 confirmed 时保存后退回 draft，需重新确认
     */
    @PutMapping("/templates/{id}/metrics")
    @SuppressWarnings("unchecked")
    public Object saveMetrics(@RequestBody Map<String, Object> body, @PathVariable long id) {
        return reportService.saveMetrics((String) body.get("token"), id,
                (List<Map<String, Object>>) body.get("metrics"));
    }

    /**
     * 确认闸门（幂等）：POST /api/report/templates/{id}/metrics/confirm，body: {token}
     * → {id, confirmed, templateStatus:"confirmed"}；重复确认返回 confirmed:0 不报错
     */
    @PostMapping("/templates/{id}/metrics/confirm")
    public Object confirmMetrics(@RequestBody Map<String, Object> body, @PathVariable long id) {
        return reportService.confirmMetrics((String) body.get("token"), id);
    }

    /**
     * 废弃单条已确认指标（软删留痕）：POST /api/report/metrics/{id}/deprecate，body: {token}
     * → {id, status:"deprecated"}；指标非 confirmed 状态时 400
     */
    @PostMapping("/metrics/{id}/deprecate")
    public Object deprecateMetric(@RequestBody Map<String, Object> body, @PathVariable long id) {
        return reportService.deprecateMetric((String) body.get("token"), id);
    }
}