package org.nearslot.framework.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件存储配置（`nearslot.storage.*`）。
 *
 * **为什么不硬编码**：存储根目录、对外 URL 前缀、大小上限、允许的扩展名
 * 都是**部署环境相关**的：
 * · 本地开发写到 ./data/uploads，生产要写挂载盘或对象存储；
 * · 公网 URL 前缀随域名/网关变化；
 * · 允许的图片格式由业务决定（是否允许 gif / heic）。
 * 写死任何一项都会导致"本地能传、生产 404"这类环境事故。
 *
 * 取值全部来自 yaml/env，切换存储后端只需改 `type`（无需改代码）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nearslot.storage")
public class StorageProperties {

    /** 存储后端：local（默认）| oss（预留，需实现 FileStorage 并注册 Bean）。 */
    private String type = "local";

    /** local 后端的物理根目录（相对或绝对路径）。 */
    private String localRoot = "data/uploads";

    /** 对外访问的 URL 前缀（如 /api/v1/files 或 https://cdn.example.com）。 */
    private String publicBaseUrl = "/api/v1/files";

    /** 单文件大小上限（字节），默认 5MB。 */
    private long maxFileSizeBytes = 5 * 1024 * 1024L;

    /** 允许的扩展名（小写、不含点），逗号分隔。默认常见图片格式。 */
    private String allowedExtensions = "jpg,jpeg,png,webp,gif";

    /** 是否校验文件头魔数（防"改扩展名上传脚本"）。 */
    private boolean verifyMagicNumber = true;
}
