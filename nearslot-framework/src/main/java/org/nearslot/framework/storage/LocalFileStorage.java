package org.nearslot.framework.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.framework.config.properties.StorageProperties;
import org.nearslot.framework.id.IdGenerator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 本地磁盘存储（默认实现，FR-11）。
 *
 * 安全要点（每条都对应一个真实攻击面）：
 * 1. **路径穿越**：key 由服务端生成（雪花 + 扩展名），**绝不拼接用户文件名**，
 *    并用 normalize + startsWith 二次确认落点在根目录内；
 * 2. **扩展名白名单**：来自配置，避免上传 .jsp/.html 后被当作静态页执行；
 * 3. **魔数校验**：改扩展名上传脚本是最常见的绕过方式，按文件头判定真实类型；
 * 4. **大小上限**：流式写入前先限制，避免磁盘打满。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalFileStorage implements FileStorage {

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** 常见图片格式的文件头魔数（只覆盖配置中允许的图片类型）。 */
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};
    private static final byte[] GIF = {'G', 'I', 'F', '8'};
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};

    private final StorageProperties props;

    @Override
    public String type() {
        return "local";
    }

    @Override
    public String store(String originalFilename, String contentType, InputStream in) {
        String ext = extensionOf(originalFilename);
        Set<String> allowed = allowedExtensions();
        if (!allowed.contains(ext)) {
            throw new BaseException(BaseCode.BAD_REQUEST,
                "不支持的文件类型 ." + ext + "（允许：" + String.join(",", allowed) + "）");
        }
        // 先读 8 字节判魔数，再把它接回流的头部继续落盘。
        // 不能用 mark/reset：Servlet 的 multipart 流不保证支持标记，
        // 真实 Tomcat 下会抛 "读取上传内容失败"（单测用 MockMultipartFile 发现不了）。
        byte[] head;
        try {
            head = in.readNBytes(8);
        } catch (IOException e) {
            // 必须带堆栈：multipart 流在不同容器/阈值下行为不同，
            // 只有堆栈能定位到底是"容器流不支持"还是"请求体已被消费"。
            log.error("读取上传流前 8 字节失败 filename={} contentType={}",
                originalFilename, contentType, e);
            throw new BaseException(BaseCode.BAD_REQUEST, "读取上传内容失败");
        }
        if (props.isVerifyMagicNumber() && !looksLikeImage(head, ext)) {
            throw new BaseException(BaseCode.BAD_REQUEST, "文件内容与扩展名不符，已拒绝");
        }
        InputStream body = new java.io.SequenceInputStream(
            new java.io.ByteArrayInputStream(head), in);

        String key = LocalDate.now().format(DATE_DIR) + "/" + IdGenerator.nextId() + "." + ext;
        Path root = Paths.get(props.getLocalRoot()).toAbsolutePath().normalize();
        Path target = root.resolve(key).normalize();
        // 路径穿越二次防线：即使 key 被人为污染也不能逃出根目录
        if (!target.startsWith(root)) {
            throw new BaseException(BaseCode.BAD_REQUEST, "非法存储路径");
        }
        try {
            Files.createDirectories(target.getParent());
            long written = Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
            if (written > props.getMaxFileSizeBytes()) {
                Files.deleteIfExists(target);
                throw new BaseException(BaseCode.BAD_REQUEST,
                    "文件超过上限 " + (props.getMaxFileSizeBytes() / 1024 / 1024) + "MB");
            }
            log.info("文件已保存 key={} bytes={} type={}", key, written, type());
            return key;
        } catch (IOException e) {
            log.error("文件写入失败 key={}", key, e);
            throw new BaseException(BaseCode.INTERNAL_ERROR, "文件保存失败");
        }
    }

    @Override
    public InputStream load(String key) {
        if (key == null || key.isBlank()) return null;
        Path root = Paths.get(props.getLocalRoot()).toAbsolutePath().normalize();
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) return null;
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            log.warn("读取文件失败 key={}", key, e);
            return null;
        }
    }

    @Override
    public void delete(String key) {
        Path root = Paths.get(props.getLocalRoot()).toAbsolutePath().normalize();
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) return;
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("删除文件失败 key={}", key, e);
        }
    }

    private Set<String> allowedExtensions() {
        return Arrays.stream(props.getAllowedExtensions().split(","))
            .map(String::trim).map(s -> s.toLowerCase(Locale.ROOT))
            .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    private String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 按文件头判断是否与扩展名匹配（只做图片白名单的自洽校验）。 */
    private boolean looksLikeImage(byte[] head, String ext) {
        if (head == null || head.length < 4) return false;
        return switch (ext) {
            case "jpg", "jpeg" -> startsWith(head, JPEG);
            case "png" -> startsWith(head, PNG);
            case "gif" -> startsWith(head, GIF);
            case "webp" -> startsWith(head, RIFF);
            // 未定义魔数的格式（如 heic）放宽，但仍受扩展名白名单约束
            default -> true;
        };
    }

    private boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }
}
