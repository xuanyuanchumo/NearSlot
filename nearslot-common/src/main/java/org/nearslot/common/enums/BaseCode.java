package org.nearslot.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 全局返回码单一事实源（C-CON-01：禁止魔法数字，Lua 与 Java 共享码表）。
 * 分段：200 成功；401xx 认证；403xx 权限；404xx 资源；429xx 限流/幂等；500xx 系统；501xx 业务；502xx AI 助手。
 */
@Getter
@AllArgsConstructor
public enum BaseCode {
    SUCCESS(200, "ok"),
    UNAUTHORIZED(40101, "未登录或会话失效"),
    TOKEN_SUPERSEDED(40102, "凭证已被顶替"),
    BAD_REQUEST(40001, "参数错误"),
    FORBIDDEN(40301, "无权限"),
    NOT_FOUND(40401, "资源不存在"),
    RATE_LIMITED(42901, "触发限流"),
    IDEMPOTENT_HIT(42902, "重复提交"),
    INTERNAL_ERROR(50001, "系统异常"),
    SLOT_NO_STOCK(50101, "余量不足"),
    SLOT_DUPLICATE(50102, "重复预约（一人一时段）"),
    SLOT_CLOSED(50103, "时段已满或已关闭"),
    LEVEL_NOT_MET(50104, "会员等级不满足"),
    WAITLIST_FULL(50105, "候补队列已满"),
    // 502xx AI 助手段（FR-5.x / agent-module-design.md §11 缺口 G1）
    AGENT_BLOCKED(50201, "该操作被安全策略拦截"),
    AGENT_NEED_CONFIRM(50202, "该操作需二次确认"),
    AGENT_CONFIRM_EXPIRED(50203, "确认已过期，请重新发起"),
    AGENT_TOOL_LIMIT(50204, "工具调用轮次超限"),
    AGENT_PLAN_EMPTY(50205, "无法理解您的需求，请换个说法"),
    AGENT_UPSTREAM_ERROR(50206, "AI 服务暂时不可用"),
    // Lua 预扣/令牌前置闸专用码（research/09 §F.1：单一枚举，杜绝 0/1/2 双口径）
    LUA_NOT_STARTED(10002, "未开始"),
    LUA_ENDED(10003, "已结束"),
    LUA_NO_STOCK_KEY(10004, "无库存键"),
    LUA_NO_STOCK(10005, "库存不足"),
    LUA_DUPLICATE(10006, "重复预约"),
    LUA_RATE_IP(10007, "IP 维度超限"),
    LUA_RATE_USER(10008, "用户维度超限");

    private final int code;
    private final String message;

    public static BaseCode of(int code) {
        for (BaseCode c : values()) if (c.code == code) return c;
        return INTERNAL_ERROR;
    }
}
