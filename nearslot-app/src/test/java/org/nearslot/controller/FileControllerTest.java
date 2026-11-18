package org.nearslot.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.common.context.UserHolder;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.framework.config.properties.StorageProperties;
import org.nearslot.framework.storage.FileStorage;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 文件上传/读取控制器单测（FR-11）：鉴权、大小闸、URL 由配置拼装。 */
class FileControllerTest {

    private FileStorage storage;
    private StorageProperties props;
    private FileController controller;

    @BeforeEach
    void setUp() {
        storage = mock(FileStorage.class);
        props = new StorageProperties();
        props.setPublicBaseUrl("/api/v1/files");
        props.setMaxFileSizeBytes(1024);
        controller = new FileController(storage, props);
    }

    @AfterEach
    void tearDown() {
        UserHolder.clear();
    }

    private MockMultipartFile file(int size) {
        return new MockMultipartFile("file", "photo.png", "image/png", new byte[size]);
    }

    @Test
    @DisplayName("upload: 未登录 -> 401（防止匿名把站点当图床）")
    void uploadRequiresLogin() {
        BaseException ex = assertThrows(BaseException.class, () -> controller.upload(file(10)));

        assertEquals(BaseCode.UNAUTHORIZED, ex.getCode());
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("upload: 空文件 -> 40001")
    void uploadRejectsEmpty() {
        UserHolder.set(1001L);

        BaseException ex = assertThrows(BaseException.class,
            () -> controller.upload(new MockMultipartFile("file", "a.png", "image/png", new byte[0])));

        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("upload: 超过配置上限 -> 40001，不进存储层")
    void uploadRejectsTooLarge() {
        UserHolder.set(1001L);

        BaseException ex = assertThrows(BaseException.class, () -> controller.upload(file(2048)));

        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("upload: 正常 -> 返回 key 与由配置前缀拼出的 url")
    void uploadBuildsUrlFromConfig() {
        UserHolder.set(1001L);
        when(storage.store(eq("photo.png"), eq("image/png"), any()))
            .thenReturn("2026/11/18/123.png");

        Map<String, Object> res = controller.upload(file(10)).getData();

        assertEquals("2026/11/18/123.png", res.get("key"));
        // URL 来自配置而非硬编码域名
        assertEquals("/api/v1/files/2026/11/18/123.png", res.get("url"));
    }

    @Test
    @DisplayName("publicUrl: 前缀带尾斜杠也不会出现双斜杠")
    void publicUrlHandlesTrailingSlash() {
        props.setPublicBaseUrl("/api/v1/files/");
        assertEquals("/api/v1/files/a/b.png", controller.publicUrl("a/b.png"));
    }

    @Test
    @DisplayName("publicUrl: 配置为绝对域名（CDN）时原样使用")
    void publicUrlSupportsCdn() {
        props.setPublicBaseUrl("https://cdn.example.com/img");
        assertEquals("https://cdn.example.com/img/a.png", controller.publicUrl("a.png"));
    }

    @Test
    @DisplayName("read: 文件不存在 -> 404（不抛异常）")
    void readMissingFile() throws Exception {
        when(storage.load(anyString())).thenReturn(null);
        var resp = new org.springframework.mock.web.MockHttpServletResponse();

        controller.read("2026/01/01/x.png", resp);

        assertEquals(404, resp.getStatus());
    }

    @Test
    @DisplayName("read: 存在 -> 200 + 图片 Content-Type + 长缓存头")
    void readExistingFile() throws Exception {
        when(storage.load(anyString()))
            .thenReturn(new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}));
        var resp = new org.springframework.mock.web.MockHttpServletResponse();

        controller.read("2026/11/18/x.png", resp);

        assertEquals(200, resp.getStatus());
        assertEquals("image/png", resp.getContentType());
        assertTrue(resp.getHeader("Cache-Control").contains("immutable"));
    }

    @Test
    @DisplayName("read: 空 key -> 404")
    void readBlankKey() throws Exception {
        var resp = new org.springframework.mock.web.MockHttpServletResponse();
        controller.read("/", resp);
        assertEquals(404, resp.getStatus());
        verifyNoInteractions(storage);
    }
}
