package com.reg.assistant.notice.impl;

import com.reg.assistant.common.ApiException;
import com.reg.assistant.notice.NoticeTemplateProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 本地目录版模板供给（M2-4）：把配置目录当作平台"模板配置管理"模块的替身。
 * 四道防线与 LocalDirHazardImageFetcher 同构，取值依据：
 * 1. 路径归一化+startsWith——文件名经 HTTP 入参/上传原始名进来，防 ../../ 目录穿越；
 * 2. 扩展名只认 .docx——poi-tl 渲染只吃这个；
 * 3. 10MB 上限——模板正常几十 KB，10MB 已极宽松，拦的是误传/恶意大文件；
 * 4. 上传同名覆盖——平台模板的"更新"语义就是同名单替换，历史通知单不受影响（已渲染的是产物）。
 */
@Component
public class LocalDirNoticeTemplateProvider implements NoticeTemplateProvider {

    private static final String ALLOWED_EXT = ".docx";
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final Path dir;

    public LocalDirNoticeTemplateProvider(@Value("${gaios.notice.template-dir:./notice-templates}") String dir) {
        this.dir = Path.of(dir).toAbsolutePath().normalize();
    }

    @Override
    public List<TemplateItem> list() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ALLOWED_EXT))
                    .map(p -> new TemplateItem(p.getFileName().toString(), sizeKb(p)))
                    .sorted(Comparator.comparing(TemplateItem::name))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    @Override
    public Path load(String templateName) {
        if (templateName == null || templateName.isBlank()) {
            throw new ApiException("请指定模板");
        }
        Path target = dir.resolve(templateName).normalize();
        if (!target.startsWith(dir)) {
            throw new ApiException("非法模板引用");
        }
        if (!Files.isRegularFile(target)) {
            throw new ApiException("模板不存在: " + templateName);
        }
        if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ALLOWED_EXT)) {
            throw new ApiException("只支持 .docx 模板");
        }
        try {
            if (Files.size(target) > MAX_BYTES) {
                throw new ApiException("模板超过 10MB 上限");
            }
        } catch (IOException e) {
            throw new ApiException("模板读取失败: " + e.getMessage());
        }
        return target;
    }

    @Override
    public Path store(String originalFilename, byte[] content) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new ApiException("缺少文件名");
        }
        // 只取文件名部分：浏览器/客户端可能带路径（含伪造的 ../../），一律剥掉
        String name = Path.of(originalFilename).getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(ALLOWED_EXT)) {
            throw new ApiException("只支持上传 .docx 模板");
        }
        if (content == null || content.length == 0) {
            throw new ApiException("文件内容为空");
        }
        if (content.length > MAX_BYTES) {
            throw new ApiException("模板超过 10MB 上限");
        }
        Path target = dir.resolve(name).normalize();
        if (!target.startsWith(dir)) {
            throw new ApiException("非法文件名");
        }
        try {
            Files.createDirectories(dir);
            // 同名覆盖：CREATE + TRUNCATE_EXISTING
            Files.write(target, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new ApiException("模板保存失败: " + e.getMessage());
        }
    }

    private static long sizeKb(Path p) {
        try {
            return Files.size(p) / 1024;
        } catch (IOException e) {
            return 0;
        }
    }
}
