package org.nearslot.appointment.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Lua 抢约/回补结果（单一码表 C-CON-01，杜绝 0/1/2 双口径）。
 *
 * 契约要点（真实 Redis 集成测试实测发现）：
 * Lua 返回的扣减字段名为 `changeQty`（见 appointmentBook.lua / appointmentRollback.lua），
 * 与 Java 侧历史命名的 `deductQty` 不一致，曾导致整条链路反序列化失败、
 * 所有抢约被判为失败。此处以 @JsonAlias 兼容两种键名，并用
 * @JsonIgnoreProperties 容忍 Lua 后续新增字段（避免字段演进而再次炸链路）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BookResult {

    /** 200 成功；10004 无库存键；10005 余量不足；10006 重复；50103 时段不可用。 */
    private int code;

    /** 扣减前余量。 */
    private int beforeQty;

    /** 扣减量（Lua 写作 changeQty）。 */
    @JsonAlias("changeQty")
    private int deductQty;

    /** 扣减后余量。 */
    private int afterQty;

    public boolean success() { return code == 200; }
}
