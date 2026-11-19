package com.reg.assistant.report.service;

import com.reg.assistant.auth.TokenService;
import com.reg.assistant.common.ApiException;
import com.reg.assistant.mapper.ReportMapper;
import com.reg.assistant.model.Entities.AssistantSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReportService {

    private final TokenService tokenService;
    private final ReportMapper reportMapper;
    private final ReportStatusMachine statusMachine;
    private final ObjectMapper om = new ObjectMapper();

    private final ReportTemplateParser reportTemplateParser;
    private final String uploadDir;
    private final long maxSizeMb;

    private final ReportCandidateExtractor reportCandidateExtractor;


    public ReportService(TokenService tokenService, ReportMapper reportMapper, ReportStatusMachine statusMachine,ReportTemplateParser reportTemplateParser,@Value("${app.report.upload-dir:data/report-uploads}") String uploadDir,@Value("${app.report.max-size-mb:20}") long maxSizeMb,ReportCandidateExtractor reportCandidateExtractor) {
        this.tokenService = tokenService;
        this.reportMapper = reportMapper;
        this.statusMachine = statusMachine;
        this.reportTemplateParser = reportTemplateParser;
        this.uploadDir = uploadDir;
        this.maxSizeMb = maxSizeMb;
        this.reportCandidateExtractor = reportCandidateExtractor;
    }

    public List<Map<String, Object>> listTemplates(String token) {
        tokenService.requireSession(token);
        return reportMapper.listTemplates();
    }

    public Map<String, Object> getTemplate(String token, long id) {
        tokenService.requireSession(token);
        Map<String, Object> t = reportMapper.findTemplateById(id);
        if (t == null) throw new ApiException(404, "报告模板不存在: " + id);
        return t;
    }

    public Map<String, Object> createInstance(String token, Long templateId, Map<String, Object> params) {
        AssistantSession session = tokenService.requireSession(token);
        Integer templateVersion = null;
        if (templateId != null) {
            Map<String, Object> t = reportMapper.findTemplateById(templateId);
            if (t == null) throw new ApiException(404, "报告模板不存在: " + templateId);
            templateVersion = ((Number) t.get("version")).intValue();
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("templateId", templateId);
        row.put("templateVersion", templateVersion);
        row.put("status", ReportStatusMachine.DRAFT);
        row.put("paramsJson", writeJson(params == null ? Map.of() : params));
        row.put("createdBy", session.getUserName());
        reportMapper.insertInstance(row);
        return getInstance(token, ((Number) row.get("id")).longValue());
    }

    /** M3-0 占位：不接真实生成任务，start 直接把 draft 推到 preview，验证状态机/幂等/守卫。 */
    public Map<String, Object> start(String token, long id) {
        tokenService.requireSession(token);
        Map<String, Object> inst = requireInstance(id);
        String status = (String) inst.get("status");
        if (ReportStatusMachine.PREVIEW.equals(status)) {
            return inst; // 幂等：重复 start 不报错
        }
        String to = statusMachine.placeholderTargetOnStart(status);
        int n = reportMapper.transitionInstance(id, status, to);
        if (n != 1) {
            throw new ApiException("报告状态已变化，请刷新后重试");
        }
        return getInstance(token, id);
    }

    public Map<String, Object> getInstance(String token, long id) {
        tokenService.requireSession(token);
        return requireInstance(id);
    }

    private Map<String, Object> requireInstance(long id) {
        Map<String, Object> inst = reportMapper.findInstanceById(id);
        if (inst == null) throw new ApiException(404, "报告实例不存在: " + id);
        return inst;
    }

    private String writeJson(Map<String, Object> v) {
        try {
            return om.writeValueAsString(v);
        } catch (Exception e) {
            throw new ApiException("报告参数序列化失败: " + e.getMessage());
        }
    }

    public Map<String, Object> uploadTemplate(String token, String originalFilename, long size, InputStream in) {
        AssistantSession session = tokenService.requireSession(token);
        if (size <= 0) throw new ApiException("文件为空");
        if (size > maxSizeMb * 1024 * 1024) throw new ApiException("文件超过大小限制: " + maxSizeMb + "MB");

        String cleanName = originalFilename == null ? "report.docx" : originalFilename.replaceAll("[\\\\/:*?\"<>|]", "_");
        String lower = cleanName.toLowerCase();
        String ext = lower.endsWith(".docx") ? "docx" : lower.endsWith(".pdf") ? "pdf" : null;
        if (ext == null) {
            throw new ApiException("仅支持 .docx/.pdf 报告模板");
        }

        try {
            Files.createDirectories(Path.of(uploadDir));
            String stored = uploadDir + "/" + System.currentTimeMillis() + "-" + cleanName;
            Files.copy(in, Path.of(stored), StandardCopyOption.REPLACE_EXISTING);

            ReportTemplateParser.ParseResult parsed;
            try (InputStream pin = Files.newInputStream(Path.of(stored))) {
                parsed = reportTemplateParser.parse(ext, pin);
            }



            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("rawText", parsed.rawText());
            spec.put("blocks", parsed.blocks());
            spec.put("stats", Map.of("chars", parsed.rawText().length(), "blocks", parsed.blocks().size()));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> blocks = (List<Map<String, Object>>) spec.get("blocks");
            List<Map<String, Object>> candidates = reportCandidateExtractor.extract(blocks);
            spec.put("candidates", candidates);
            spec.put("candidateStats", Map.of(
                    "count", candidates.size(),
                    "paragraph", candidates.stream().filter(c -> "paragraph".equals(c.get("source"))).count(),
                    "table", candidates.stream().filter(c -> "table".equals(c.get("source"))).count()
            ));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", cleanName.replaceFirst("\\.(docx|doc)$", ""));
            row.put("sourceFileName", cleanName);
            row.put("status", parsed.error() == null ? "draft" : "failed");
            row.put("version", 1);
            row.put("specJson", writeJson(spec));
            row.put("createdBy", session.getUserName());
            row.put("storagePath", stored);
            row.put("parseMode", parsed.parseMode());
            row.put("blockCount", parsed.blocks().size());
            row.put("parseError", parsed.error());
            reportMapper.insertTemplate(row);
            return getTemplate(token, ((Number) row.get("id")).longValue());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("报告模板上传失败: " + e.getMessage());
        }
    }

    /**
     * 可重复触发的抽取接口
     * @param token
     * @param id
     * @return
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> reExtractCandidates(String token, long id) {
        tokenService.requireSession(token);
        Map<String, Object> t = reportMapper.findTemplateParseById(id);
        if (t == null) throw new ApiException(404, "报告模板不存在: " + id);
        String specJson = (String) t.get("specJson");
        Map<String, Object> spec;
        try {
            spec = om.readValue(specJson, Map.class);
        } catch (Exception e) {
            throw new ApiException("模板 spec_json 解析失败: " + e.getMessage());
        }
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) spec.get("blocks");
        List<Map<String, Object>> candidates = reportCandidateExtractor.extract(blocks);
        spec.put("candidates", candidates);
        spec.put("candidateStats", Map.of("count", candidates.size()));
        reportMapper.updateTemplateSpec(id, writeJson(spec), blocks == null ? 0 : blocks.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("candidateCount", candidates.size());
        result.put("candidates", candidates);
        return result;
    }

    public Map<String, Object> getTemplateRaw(String token, long id) {
        tokenService.requireSession(token);
        Map<String, Object> t = reportMapper.findTemplateParseById(id);
        if (t == null) throw new ApiException(404, "报告模板不存在: " + id);
        return t;
    }
}