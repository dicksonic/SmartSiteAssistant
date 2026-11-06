package com.reg.assistant.controller;

import com.reg.assistant.auth.TokenService;
import com.reg.assistant.model.Entities.AssistantSession;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 平台接入：已建B/S平台"图标入口+弹层iframe"接入助手时的免登认证 */
@RestController
@RequestMapping("/api/integration")
public class IntegrationController {

    private final TokenService tokenService;

    public IntegrationController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    /** 生产：平台后端签名换Token */
    @PostMapping("/issueAccessToken")
    public Map<String, Object> issueAccessToken(@RequestBody Map<String, Object> body) {
        AssistantSession s = tokenService.issue(
                (String) body.get("appKey"),
                (String) body.get("signature"),
                ((Number) body.get("timestamp")).longValue(),
                (String) body.get("userName"),
                (String) body.get("orgName"));
        return Map.of("token", s.getToken(), "expiresAt", s.getExpiresAt().toString());
    }

    /** 演示：模拟平台侧签名（仅 is_demo 平台可用） */
    @PostMapping("/demoSign")
    public Map<String, Object> demoSign(@RequestBody Map<String, Object> body) {
        AssistantSession s = tokenService.demoSign(
                (String) body.get("appKey"),
                (String) body.get("userName"),
                (String) body.get("orgName"));
        return Map.of("token", s.getToken(), "expiresAt", s.getExpiresAt().toString());
    }

    /** 会话信息：助手前端进入时校验Token */
    @GetMapping("/sessionInfo")
    public Map<String, Object> sessionInfo(@RequestParam String token) {
        AssistantSession s = tokenService.requireSession(token);
        return Map.of(
                "userName", s.getUserName(),
                "orgName", s.getOrgName() == null ? "" : s.getOrgName(),
                "platformName", s.getPlatformName() == null ? "未知平台" : s.getPlatformName(),
                "expiresAt", s.getExpiresAt().toString());
    }
}
