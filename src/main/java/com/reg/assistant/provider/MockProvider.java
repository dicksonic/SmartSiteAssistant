package com.reg.assistant.provider;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模拟模型供应商：无真实API Key时使用，返回确定性演示数据并模拟延迟与用量。
 * 切换真实API只需改 application.yml 的 model.mode=http，业务代码零改动。
 */
@Component
public class MockProvider implements ModelProvider {

    @Override
    public String name() {
        return "mock";
    }

    private void latency() {
        try {
            Thread.sleep(200 + ThreadLocalRandom.current().nextInt(500));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private double estTokens(String text) {
        // 粗略估算：中英混合约 1 token ≈ 1.5 字符 → 千tokens
        return Math.ceil(text.length() / 1.5 / 100) / 10.0;
    }

    @Override
    public CallResult<String> chat(String system, String prompt, List<String> context) {
        latency();
        String reply = buildChatReply(system, prompt);
        double amount = estTokens(system + prompt + String.join("\n", context) + reply);
        return new CallResult<>(reply, new Usage("text", amount, "千tokens"), name(), "mock-text-1");
    }

    @Override
    public CallResult<String> vision(String imageBase64, String prompt) {
        latency();
        String data = "{\"objects\":[\"临边防护缺失\",\"未系安全带\",\"材料堆放杂乱\"],\"scene\":\"楼层临边作业面\",\"riskLevel\":\"较大\"}";
        return new CallResult<>(data, new Usage("vision", 1, "次"), name(), "mock-vision-1");
    }

    @Override
    public CallResult<String> asr(String audioBase64) {
        latency();
        return new CallResult<>("深基坑支护关键部位的检查要点有哪些", new Usage("asr", 30, "秒"), name(), "mock-asr-1");
    }

    @Override
    public CallResult<String> tts(String text) {
        latency();
        // mock 不生成音频，前端降级为浏览器 speechSynthesis 播报
        return new CallResult<>(null, new Usage("tts", text.length(), "字符"), name(), "mock-tts-1");
    }

    /** 按系统角色返回对应场景的结构化演示数据 */
    private String buildChatReply(String system, String prompt) {
        if (system.contains("隐患记录员")) {
            String location = "未指定";
            Matcher m = Pattern.compile("部位：(.+)").matcher(prompt);
            if (m.find()) location = m.group(1);
            return """
                    {"category":"高处作业/临边防护","location":"%s",
                    "description":"经查，作业楼层临边未按规定设置防护栏杆，现场作业人员未系挂安全带，存在高处坠落风险。该隐患违反《建筑施工高处作业安全技术规范》JGJ80关于临边作业防护的规定。",
                    "rectification":"立即暂停该区域作业；24小时内按规范设置1.2m高防护栏杆并加挂密目安全网、设置挡脚板；作业人员全员重新进行高处作业安全技术交底，正确佩戴安全带并高挂低用；整改完成后报监理复核。",
                    "basis":"JGJ80《建筑施工高处作业安全技术规范》、JGJ59《建筑施工安全检查标准》"}
                    """.formatted(location);
        }
        if (system.contains("资料审核员")) {
            return """
                    {"missingItems":["编制人、审核人、审批人签字及日期","施工单位技术负责人审批签章页","专家论证意见（深基坑属超过一定规模危大工程，需论证）"],
                    "logicIssues":[
                      {"location":"第1页","issue":"基坑深度6.5米，但未附相应的支护结构计算书与监测方案","suggestion":"补充支护设计计算书与监测专项方案"},
                      {"location":"全文","issue":"方案未明确应急救援预案与物资清单","suggestion":"按危大工程管理要求补充应急章节"}
                    ],"verdict":"reject"}
                    """;
        }
        if (system.contains("整改复核员")) {
            return """
                    {"textConsistent":true,"verdict":"rectified",
                    "conclusion":"整改后描述显示临边已按规范设置1.2m防护栏杆并挂安全网，与整改前隐患（无防护栏杆）形成对应闭合，整改措施与规范要求一致，初步判断整改到位。建议现场复核确认后闭环。"}
                    """;
        }
        return """
                {"answer":"针对提问，依据现行验收规范，关键部位检查要点如下，请逐项核对。",
                "checklist":[
                  "支护结构：检查排桩/地下连续墙完整性，桩顶位移监测值是否在预警值内",
                  "支撑体系：检查内支撑轴力监测数据、立柱桩沉降、支撑连接节点",
                  "地下水控制：检查降水井运行状态、坑内外水位观测记录",
                  "坑边荷载：检查坑边2m范围内堆载与机械停放是否符合方案",
                  "临边防护：检查坑边防护栏杆高度（≥1.2m）与警示标志"
                ],
                "references":["《建筑基坑工程监测技术标准》GB50497","《建筑施工安全检查标准》JGJ59"]}
                """;
    }
}
