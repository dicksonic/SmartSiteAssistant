package com.reg.assistant.hazard.impl;

import com.reg.assistant.common.ApiException;
import com.reg.assistant.hazard.HazardImageFetcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 本地目录版取图（M2-2）：把配置目录当作"业务系统附件库"的替身。
 * 三道防线的取值依据：
 * 1. 路径归一化+startsWith 校验——imageRef 来自 HTTP 入参，防 ../../ 目录穿越；
 * 2. 扩展名白名单与 QwenVLCore 支持格式对齐；
 * 3. 10MB 上限与 QwenVLCore MAX_IMAGE_BYTES 对齐，提前在 web 侧拦，不给内核留大请求体。
 */
@Component
public class LocalDirHazardImageFetcher implements HazardImageFetcher {

    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp");
    private static final long MAX_BYTES = 10L * 1024 * 1024; // 与 QwenVLCore.MAX_IMAGE_BYTES 对齐

    private final Path dir;

    public LocalDirHazardImageFetcher(@Value("${hazard.image.dir:./sample-images}") String dir) {
        this.dir = Path.of(dir).toAbsolutePath().normalize();
    }

    @Override
    public Path fetch(String imageRef) {
        if (imageRef == null || imageRef.isBlank()) {
            throw new ApiException("请指定图片");
        }
        Path target = dir.resolve(imageRef).normalize();
        if (!target.startsWith(dir)) {
            throw new ApiException("非法图片引用");
        }
        if (!Files.isRegularFile(target)) {
            throw new ApiException("图片不存在: " + imageRef);
        }
        String name = target.getFileName().toString().toLowerCase(Locale.ROOT);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        if (!ALLOWED_EXT.contains(ext)) {
            throw new ApiException("不支持的图片格式: " + ext);
        }
        try {
            if (Files.size(target) > MAX_BYTES) {
                throw new ApiException("图片超过 10MB 上限，请先压缩");
            }
        } catch (IOException e) {
            throw new ApiException("图片读取失败: " + e.getMessage());
        }
        return target;
    }

    @Override
    public List<ImageItem> list() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return ALLOWED_EXT.stream().anyMatch(n::endsWith);
                    })
                    .map(p -> new ImageItem(p.getFileName().toString(), sizeKb(p)))
                    .sorted(Comparator.comparing(ImageItem::name))
                    .toList();
        } catch (IOException e) {
            return List.of();
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