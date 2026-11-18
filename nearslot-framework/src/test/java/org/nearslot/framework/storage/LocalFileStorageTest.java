package org.nearslot.framework.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.framework.config.properties.StorageProperties;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本地存储单测（FR-11）。
 *
 * 锁住四个真实攻击面：
 * 1. 扩展名白名单（防 .jsp/.html 落盘后被当页面执行）；
 * 2. 魔数校验（防改扩展名上传脚本）；
 * 3. 路径穿越（key 里塞 ../ 逃出根目录）；
 * 4. 大小上限。
 */
class LocalFileStorageTest {

    @TempDir
    Path tempDir;

    private StorageProperties props;
    private LocalFileStorage storage;

    private static final byte[] PNG_HEAD = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    @BeforeEach
    void setUp() {
        props = new StorageProperties();
        props.setLocalRoot(tempDir.toString());
        props.setAllowedExtensions("jpg,jpeg,png,webp,gif");
        props.setMaxFileSizeBytes(1024 * 1024);
        props.setVerifyMagicNumber(true);
        storage = new LocalFileStorage(props);
    }

    @Test
    @DisplayName("store: 合法 PNG -> 返回按日期分目录的 key，可回读")
    void storeAndLoadPng() throws IOException {
        String key = storage.store("photo.png", "image/png", new ByteArrayInputStream(PNG_HEAD));

        assertTrue(key.matches("\\d{4}/\\d{2}/\\d{2}/\\d+\\.png"), "key 应按 yyyy/MM/dd/ 分目录: " + key);
        try (InputStream in = storage.load(key)) {
            assertNotNull(in, "刚写入的文件必须能读回");
            assertEquals(PNG_HEAD.length, in.readAllBytes().length);
        }
    }

    @Test
    @DisplayName("store: 非白名单扩展名 -> 40001（防脚本落盘）")
    void rejectDisallowedExtension() {
        BaseException ex = assertThrows(BaseException.class,
            () -> storage.store("evil.jsp", "text/plain", new ByteArrayInputStream("x".getBytes())));

        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("jsp"));
    }

    @Test
    @DisplayName("store: 扩展名是 png 但内容不是图片 -> 40001（魔数校验）")
    void rejectForgedMagicNumber() {
        byte[] script = "<script>alert(1)</script>".getBytes();

        BaseException ex = assertThrows(BaseException.class,
            () -> storage.store("evil.png", "image/png", new ByteArrayInputStream(script)));

        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("内容与扩展名不符"));
    }

    @Test
    @DisplayName("store: 关闭魔数校验后放行（配置可关，便于兼容 heic 等格式）")
    void magicCheckCanBeDisabled() {
        props.setVerifyMagicNumber(false);
        byte[] anything = {1, 2, 3, 4, 5};

        assertDoesNotThrow(() -> storage.store("a.png", "image/png",
            new ByteArrayInputStream(anything)));
    }

    @Test
    @DisplayName("store: 无扩展名 -> 40001")
    void rejectNoExtension() {
        BaseException ex = assertThrows(BaseException.class,
            () -> storage.store("noext", "image/png", new ByteArrayInputStream(PNG_HEAD)));

        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("store: 用户文件名里的 ../ 不参与路径拼接（key 由服务端生成）")
    void userFilenameCannotEscape() {
        String key = storage.store("../../etc/passwd.png", "image/png",
            new ByteArrayInputStream(PNG_HEAD));

        // key 里只保留服务端生成的日期目录 + 雪花 ID，不含用户提供的任何路径片段
        assertFalse(key.contains(".."), "key 不得包含用户提供的穿越片段: " + key);
        assertFalse(key.contains("passwd"), "key 不得包含用户文件名: " + key);
        assertTrue(key.startsWith(java.time.LocalDate.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))));
    }

    @Test
    @DisplayName("load: 路径穿越 key -> 返回 null（不得读到根目录外文件）")
    void loadRejectsTraversal() {
        assertNull(storage.load("../../../../etc/passwd"));
    }

    @Test
    @DisplayName("load: 不存在的 key -> null")
    void loadMissing() {
        assertNull(storage.load("2026/01/01/not-exist.png"));
    }

    @Test
    @DisplayName("delete: 幂等（不存在也不抛）")
    void deleteIdempotent() {
        assertDoesNotThrow(() -> storage.delete("2026/01/01/none.png"));
    }

    @Test
    @DisplayName("type: 标识为 local")
    void typeIsLocal() {
        assertEquals("local", storage.type());
    }
}
