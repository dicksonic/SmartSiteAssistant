package com.reg.assistant.notice;

import java.nio.file.Path;
import java.util.List;

/**
 * 通知单模板供给抽象（M2-4）：与 HazardImageFetcher 同一打法——接口先行，
 * 第一版=本地目录模拟平台"模板配置管理"模块，接真平台时换实现不改主链。
 * 模板是数据不是代码：办公人员可上传更换，槽位集合由模板自身决定。
 */
public interface NoticeTemplateProvider {

    record TemplateItem(String name, long sizeKb) {}

    /** 模板清单（仅 .docx），按名称排序 */
    List<TemplateItem> list();

    /** 取模板文件：四防线校验（穿越/存在/扩展名/大小），供槽位解析与渲染共用 */
    Path load(String templateName);

    /** 办公人员上传模板：同名覆盖（模板更新就是换个同名文件） */
    Path store(String originalFilename, byte[] content);
}
