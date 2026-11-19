package com.reg.assistant.report;

import com.reg.assistant.report.service.ReportService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
}