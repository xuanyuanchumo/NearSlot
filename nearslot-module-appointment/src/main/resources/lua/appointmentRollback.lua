-- 回补脚本（ADR-005：反转 before/after 语义；del 库存键等待重载防漂移）
-- KEYS[1]=slot:stock:{slotId} KEYS[2]=slot:user:{slotId}
-- KEYS[3]=slot:trace:{slotId} KEYS[4]=staff:timeline:{techId}
-- ARGV[1]=userId [2]=slotId [3]=techId [4]=startTs [5]=orderId [6]=traceId
-- ARGV[7]=operate(1=同时移除用户标记) [8]=ttlSeconds [9]=endTs
local stockKey, userKey, traceKey, timelineKey = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
local userId, slotId, techId = ARGV[1], ARGV[2], ARGV[3]
local startTs, orderId, traceId = ARGV[4], ARGV[5], ARGV[6]
local operate, ttl = tonumber(ARGV[7]), tonumber(ARGV[8])
local endTs = ARGV[9]

local stock = redis.call('GET', stockKey)
if not stock then return cjson.encode({code = 10004}) end
local before = tonumber(stock)

-- del 键而非 INCR 回补：避免回补值漂移，等待下次 loadStock 重载
redis.call('DEL', stockKey)
if operate == 1 then
  redis.call('SREM', userKey, userId)
end
-- 时间轴 member 结构与 book 一致（"slotId#endsAt"）
redis.call('ZREM', timelineKey, slotId .. '#' .. endTs)

-- 反转语义：beforeQty/afterQty 交换
local entry = cjson.encode({
  logType = 2, ts = tonumber(redis.call('TIME')[1]), orderId = orderId,
  traceId = traceId, userId = userId, slotId = slotId,
  beforeQty = before + 1, changeQty = 1, afterQty = before
})
redis.call('HSET', traceKey, traceId, entry)
redis.call('EXPIRE', traceKey, ttl)

return cjson.encode({code = 200, beforeQty = before + 1, changeQty = 1, afterQty = before})
