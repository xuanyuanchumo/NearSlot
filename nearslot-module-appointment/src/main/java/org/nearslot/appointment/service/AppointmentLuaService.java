package org.nearslot.appointment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.appointment.domain.BookResult;
import org.nearslot.framework.cache.RedisConstants;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 抢约 Lua 执行器（三重检查 + trace）。
 * 返回码统一走 BaseCode 的 100xx/501xx 段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentLuaService {
    private final StringRedisTemplate redis;
    /** 复用 Spring 容器中的 ObjectMapper（成熟配置：已注册 JavaTime、宽容未知字段），不手 new。 */
    private final ObjectMapper mapper;

    private static final DefaultRedisScript<String> BOOK_SCRIPT = loadScript("lua/appointmentBook.lua");
    private static final DefaultRedisScript<String> ROLLBACK_SCRIPT = loadScript("lua/appointmentRollback.lua");

    private static DefaultRedisScript<String> loadScript(String path) {
        DefaultRedisScript<String> s = new DefaultRedisScript<>();
        try {
            s.setScriptText(StreamUtils.copyToString(
                new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Lua 脚本加载失败: " + path, e);
        }
        s.setResultType(String.class);
        return s;
    }

    /**
     * 抢约：时间闸 + 余量 + 一人一时段 + 技师时间轴，原子完成。
     *
     * @param capacity 时段容量，用于余量键缺失时的兜底重建（Redis 过期/重启后
     *                 不应让整个时段永久不可约——原实现直接返回 10004）
     */
    public BookResult book(long userId, long slotId, long techId,
                           long startTs, long endTs, long orderId, long traceId, int capacity) {
        String raw = redis.execute(BOOK_SCRIPT,
            List.of(RedisConstants.SLOT_STOCK + slotId,
                    RedisConstants.SLOT_USER + slotId,
                    RedisConstants.SLOT_TRACE + slotId,
                    RedisConstants.PREFIX + "staff:timeline:" + techId),
            String.valueOf(userId), String.valueOf(slotId), String.valueOf(techId),
            String.valueOf(startTs), String.valueOf(endTs),
            String.valueOf(orderId), String.valueOf(traceId),
            "86400", String.valueOf(Math.max(capacity, 1)),
            // nowTs 由服务端时钟给出，避免 Java 与 Lua 两处时间口径分裂
            String.valueOf(System.currentTimeMillis()));
        return parse(raw);
    }

    /**
     * 回补（ADR-005 反转语义）。
     *
     * @param capacity 时段容量，用于余量键缺失时重建与 INCR 后封顶（防超卖）
     */
    public BookResult rollback(long userId, long slotId, long techId,
                               long startTs, long endTs, long orderId, long traceId,
                               int operate, int capacity) {
        String raw = redis.execute(ROLLBACK_SCRIPT,
            List.of(RedisConstants.SLOT_STOCK + slotId,
                    RedisConstants.SLOT_USER + slotId,
                    RedisConstants.SLOT_TRACE + slotId,
                    RedisConstants.PREFIX + "staff:timeline:" + techId),
            String.valueOf(userId), String.valueOf(slotId), String.valueOf(techId),
            String.valueOf(startTs), String.valueOf(orderId), String.valueOf(traceId),
            String.valueOf(operate), "86400", String.valueOf(endTs),
            String.valueOf(Math.max(capacity, 1)),
            // ARGV[11] nowTs：脚本据此计算审计时间戳，
            // 避免在写操作之后再调 TIME（Redis 禁止非确定性命令后写数据，实测直接 500）。
            String.valueOf(System.currentTimeMillis()));
        return parse(raw);
    }

    private BookResult parse(String raw) {
        try {
            return mapper.readValue(raw == null ? "{\"code\":50001}" : raw, BookResult.class);
        } catch (IOException e) {
            log.error("Lua 返回解析失败: {}", raw, e);
            BookResult r = new BookResult();
            r.setCode(50001);
            return r;
        }
    }
}
