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
    public static final long TTL_8D = 8L * 24 * 3600;
    public static final long TTL_30MIN = 30 * 60L;
    public static final long TTL_NULL = 120L;
    public static final long TTL_LOCK = 10L;
}
