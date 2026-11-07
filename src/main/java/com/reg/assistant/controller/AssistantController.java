package com.reg.assistant.controller;

import com.reg.assistant.agent.service.AgentService;
import com.reg.assistant.service.AssistantService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 四大应用场景接口 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService assistantService;

    private final AgentService agentService;

    public AssistantController(AssistantService assistantService, AgentService agentService) {
        this.assistantService = assistantService;
        this.agentService = agentService;
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
}
