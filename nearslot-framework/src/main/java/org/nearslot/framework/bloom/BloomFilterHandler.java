package org.nearslot.framework.bloom;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/** 布隆过滤器工厂（可配容量/误判率，防缓存穿透）。生产可换 RedisBloom。 */
public class BloomFilterHandler {
    private final ConcurrentHashMap<String,BloomFilter<String>> filters = new ConcurrentHashMap<>();
    public void init(String name, long expected, double fpp) {
        filters.put(name, BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), expected, fpp));
    }
    public boolean mightContain(String name, String value) {
        BloomFilter<String> f = filters.get(name);
        return f != null && f.mightContain(value);
    }
    public void put(String name, String value) {
        BloomFilter<String> f = filters.get(name);
        if (f != null) f.put(value);
    }
}
