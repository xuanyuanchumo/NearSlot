package org.nearslot.framework.storage;

import java.io.InputStream;

/**
 * 文件存储 SPI（FR-11）。
 *
 * **为什么要抽接口**：本地开发用磁盘、生产用 OSS，这是部署差异而非业务差异。
 * 业务代码只依赖本接口，切换后端只换 Spring Bean（配置 `nearslot.storage.type`），
 * 不需要改任何业务或 Controller。
 *
 * 实现方必须保证：
 * 1. 拒绝路径穿越（文件名不得含 `..` / 分隔符逃逸）；
 * 2. 校验大小与扩展名（配置来自 {@code StorageProperties}）；
 * 3. 返回**可直接对外访问**的相对 key，由调用方拼 publicBaseUrl。
 */
public interface FileStorage {

    /**
     * 保存文件。
     *
     * @param originalFilename 原始文件名（用于取扩展名）
     * @param contentType      客户端声明的 MIME
     * @param in               文件流
     * @return 存储 key（相对路径，如 `2026/11/18/abc123.jpg`）
     */
    String store(String originalFilename, String contentType, InputStream in);

    /**
     * 读取文件。
     *
     * @param key 存储 key
     * @return 文件流；不存在返回 null
     */
    InputStream load(String key);

    /** 删除（幂等）。 */
    void delete(String key);

    /** 后端类型标识，用于日志与健康检查。 */
    String type();
}
