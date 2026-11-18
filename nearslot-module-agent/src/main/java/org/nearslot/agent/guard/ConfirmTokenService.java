package org.nearslot.agent.guard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.framework.cache.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 二次确认令牌（FR-5.2 / 双闸口之二）。
 *
 * 流程：CONFIRM 判定 -> 生成 token 存 Redis（TTL 300s）-> SSE 下发 confirm 事件
 *      -> 用户确认 -> 带 token 调用 -> 服务端校验并删除（一次性）。
 *
 * 安全要点：① token 一次性（校验后立即 del）防重放；② TTL 300s 对齐 research/04；
 * ③ 绑定 userId，防他人窃取 token 后操作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmTokenService {

    /** 确认令牌 TTL（秒），对齐 research/04 双闸口实证值。 */
    /** 令牌有效期外置到 `nearslot.agent.confirm-token-ttl-seconds`。 */

    private final StringRedisTemplate redis;
    private final org.nearslot.agent.config.AgentProperties props;

    private String key(String token) {
        return RedisConstants.PREFIX + "agent:confirm:" + token;
    }

    /** 生成确认令牌，值为 userId:summary。 */
    public String issue(Long userId, String summary) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(key(token), userId + ":" + summary, props.getConfirmTokenTtlSeconds(), TimeUnit.SECONDS);
        log.info("签发确认令牌 userId={} summary={}", userId, summary);
        return token;
    }

    /**
     * 消费令牌并返回被挂起的原始指令（一次性）。
     * 评审 D-002：只有 consume() 返回 bool 无法知道"要执行什么"，确认流程必然落空。
     *
     * @return 原始指令；令牌无效/归属不符/已消费返回 null
     */
    public String consumeAndGet(Long userId, String token) {
        if (token == null || token.isBlank()) return null;
        String k = key(token);
        String v = redis.opsForValue().get(k);
        if (v == null) return null;
        int idx = v.indexOf(":");
        if (idx <= 0) return null;
        String owner = v.substring(0, idx);
        if (!String.valueOf(userId).equals(owner)) {
            log.warn("确认令牌归属不符 userId={} owner={}", userId, owner);
            return null;
        }
        // 先删后返回：一次性语义，防止并发重放
        redis.delete(k);
        return v.substring(idx + 1);
    }

    /** 消费令牌（一次性）。true=校验通过并已作废。 */
    public boolean consume(Long userId, String token) {
        if (token == null || token.isBlank()) return false;
        String k = key(token);
        String v = redis.opsForValue().get(k);
        if (v == null) return false;
        int idx = v.indexOf(":");
        if (idx <= 0) return false;
        String owner = v.substring(0, idx);
        if (!String.valueOf(userId).equals(owner)) {
            log.warn("确认令牌归属不符 userId={} owner={}", userId, owner);
            return false;
        }
        redis.delete(k);
        return true;
    }
}
