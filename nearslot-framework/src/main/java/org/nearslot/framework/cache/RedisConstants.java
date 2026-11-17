package org.nearslot.framework.cache;

/** Redis 键规范（schema.md §4：nearslot:{domain}:{biz}:{id}，TTL 显式）。 */
public final class RedisConstants {
    private RedisConstants() {}
    public static final String PREFIX = "nearslot:";
    public static final String SLOT_STOCK = PREFIX + "slot:stock:";
    public static final String SLOT_USER = PREFIX + "slot:user:";
    public static final String SLOT_TRACE = PREFIX + "slot:trace:";
    public static final String CANDIDATE = PREFIX + "candidate:";
    public static final String TECH_RANK = PREFIX + "tech:rank:";
    public static final String CACHE_SHOP = PREFIX + "cache:shop:";
    public static final String CACHE_NULL = PREFIX + "cache:null:";
    public static final String LOCK = PREFIX + "lock:";
    public static final String IDEM = PREFIX + "idem:";
    public static final String TOKEN_ACCESS = PREFIX + "login:token:access:";
    public static final String TOKEN_REFRESH = PREFIX + "login:token:refresh:";
    public static final String TOKEN_VERSION_VALID = PREFIX + "token:version:valid:";
    public static final String TOKEN_VERSION_CURRENT = PREFIX + "token:version:current:";
    // ===== 登录防爆破（对齐 PickNear RedisConstants 精确值）=====
    /** 账号维度失败计数：24h 累计；连续 10 次锁定。 */
    public static final String LOGIN_FAIL_COUNT = PREFIX + "login:fail:count:";
    public static final long TTL_FAIL_COUNT = 86400L;
    public static final int MAX_FAIL_COUNT = 10;
    /** 账号锁：锁 30 分钟。 */
    public static final String LOGIN_LOCK = PREFIX + "login:lock:";
    public static final long TTL_LOGIN_LOCK = 1800L;
    /** IP 维度限频：60s 内最多 5 次。 */
    public static final String LOGIN_FAIL_IP = PREFIX + "login:fail:ip:";
    public static final long TTL_FAIL_IP = 60L;
    public static final int MAX_FAIL_IP = 5;
    /** 验证码键。 */
    public static final String LOGIN_CODE = PREFIX + "login:code:";

    // ===== 候补/订阅（对齐 hmdp-plus 三键设计）=====
    /** 候补队列 ZSet：member=userId, score=入队时间戳。 */
    public static final String SUBSCRIBE_ZSET = PREFIX + "subscribe:zset:";
    /** 候补用户集合 Set：幂等去重。 */
    public static final String SUBSCRIBE_USER = PREFIX + "subscribe:user:";
    /** 候补状态 Hash：field=userId, value=状态码（1订阅 2成功）。 */
    public static final String SUBSCRIBE_STATUS = PREFIX + "subscribe:status:";

    public static final long TTL_8D = 8L * 24 * 3600;
    public static final long TTL_30MIN = 30 * 60L;
    public static final long TTL_NULL = 120L;
    public static final long TTL_LOCK = 10L;
}
