package com.reg.assistant.report.provider;

/**
 * 取数规则（代码白名单注册，非库表配置）。
 * 规则 = 一段预置聚合 SQL + 输出列约定；指标只通过 ruleCode 引用规则——
 * 前端/模板任何字段都不会拼进 SQL（注入防线 + 国产库方言纪律：方言只可能出现在 Mapper 一处）。
 *
 * @param ruleCode      规则编码（绑定键，大写下划线）
 * @param version       规则版本（绑定时由注册表权威写入指标，不信客户端传值；
 *                      口径变更必须升版本，旧绑定会被显式打断要求重新确认，防"同名规则悄悄变了口径"）
 * @param domain        业务域（hazard=隐患 / platform=平台运行）
 * @param name          规则名（绑定下拉展示）
 * @param description   口径说明（含输出列约定，给人看）
 * @param suggestedUnit 建议单位（指标保存时 unit 缺省可参考，不强制）
 */
public record ReportRule(String ruleCode, String version, String domain,
                         String name, String description, String suggestedUnit) {
}
