package org.nearslot.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.common.Result;
import org.nearslot.common.context.UserHolder;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.framework.config.properties.StorageProperties;
import org.nearslot.framework.storage.FileStorage;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 文件上传与读取（FR-11）。
 *
 * **可配置项全部来自 `nearslot.storage.*`**：后端类型、物理根目录、对外 URL 前缀、
 * 大小上限、允许格式。本地磁盘与服务对象存储的切换只改配置，本控制器零改动。
 *
 * 鉴权取舍：
 * · 上传必须登录 —— 否则站点会被当匿名图床（两个参考项目的 UploadController 也未鉴权，属已知缺陷）；
 * · 读取公开 —— 笔记配图/店铺封面需要未登录也能浏览。
 */
@Slf4j
@Tag(name = "文件", description = "图片上传与读取（FR-11）")
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorage storage;
    private final StorageProperties props;

    @Operation(summary = "上传文件", description = "需登录；扩展名白名单 + 大小上限 + 魔数校验")
    @PostMapping
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        if (UserHolder.get() == null) {
            throw new BaseException(BaseCode.UNAUTHORIZED);
        }
        if (file == null || file.isEmpty()) {
            throw new BaseException(BaseCode.BAD_REQUEST, "文件不能为空");
        }
        if (file.getSize() > props.getMaxFileSizeBytes()) {
            throw new BaseException(BaseCode.BAD_REQUEST,
                "文件超过上限 " + (props.getMaxFileSizeBytes() / 1024 / 1024) + "MB");
        }
        try (InputStream in = file.getInputStream()) {
            String key = storage.store(file.getOriginalFilename(), file.getContentType(), in);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("key", key);
            res.put("url", publicUrl(key));
            res.put("size", file.getSize());
            log.info("上传成功 key={} size={} by={}", key, file.getSize(), UserHolder.get());
            return Result.ok(res);
        } catch (IOException e) {
            log.error("上传读取流失败", e);
            throw new BaseException(BaseCode.INTERNAL_ERROR, "上传失败");
        }
    }

    /**
     * 读取文件（公开只读）。
     *
     * `{*key}` 是 Spring 6+ 的 catch-all 段，能捕获 `2026/11/18/xxx.jpg` 这类含斜杠的 key，
     * 比在方法里手工截 URI 更可靠（也避免把路由规则散落到代码里）。
     */
    @Operation(summary = "读取文件", description = "公开只读；key 由上传接口返回")
    @GetMapping("/{*key}")
    public void read(@PathVariable("key") String key, HttpServletResponse response) throws IOException {
        String normalized = key == null ? "" : key.replaceFirst("^/+", "");
        if (normalized.isBlank()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        try (InputStream in = storage.load(normalized)) {
            if (in == null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            response.setContentType(contentTypeOf(normalized));
            // 内容由服务端生成且 key 不可变，可长缓存；但必须 public 才能在 CDN 生效
            response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
            in.transferTo(response.getOutputStream());
        }
    }

    /** 对外访问 URL：配置前缀 + key，避免把域名写死进业务。 */
    public String publicUrl(String key) {
        String base = props.getPublicBaseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/" + key;
    }

    /**
     * 按扩展名给出 Content-Type。
     *
     * 这里的映射属于**协议常量**（MIME 是标准，不随环境变化），故不配置化；
     * 未知扩展名一律 octet-stream，绝不让浏览器把它当可执行内容渲染。
     */
    private String contentTypeOf(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG_VALUE;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG_VALUE;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF_VALUE;
        if (lower.endsWith(".webp")) return "image/webp";
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }
}
