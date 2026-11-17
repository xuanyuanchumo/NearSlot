-- 抢约原子脚本（NearSlot 特色：三重检查 + 审计 trace）
-- KEYS[1] = slot:stock:{slotId}   String 余量
-- KEYS[2] = slot:user:{slotId}    Set 已约用户
-- KEYS[3] = slot:trace:{slotId}   Hash traceId -> 日志
-- KEYS[4] = staff:timeline:{techId} ZSet member="slotId#endsAt" score=startsAt（技师时间轴）
-- ARGV[1]=userId  [2]=slotId  [3]=techId  [4]=startTs  [5]=endTs
-- ARGV[6]=orderId [7]=traceId [8]=ttlSeconds [9]=capacity
local stockKey, userKey, traceKey, timelineKey = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
local userId, slotId, techId = ARGV[1], ARGV[2], ARGV[3]
local startTs, endTs = tonumber(ARGV[4]), tonumber(ARGV[5])
local orderId, traceId = ARGV[6], ARGV[7]
local ttl, capacity = tonumber(ARGV[8]), tonumber(ARGV[9])

-- 检查 1：一人一时段
if redis.call('SISMEMBER', userKey, userId) == 1 then
  return cjson.encode({code = 10006})
end

-- 检查 2：技师时间轴重叠（仅对「不同 slot」判定）
-- member 格式 "slotId#endsAt"：同一 slot 的并发请求由此跳过，
-- 避免「同一时段余量竞争」被误判为「时段被占」而返回 50103。
local conflicts = redis.call('ZRANGEBYSCORE', timelineKey, '-inf', '+' .. startTs)
for _, member in ipairs(conflicts) do
  local sep = string.find(member, '#')
  if sep then
    local otherSlot = string.sub(member, 1, sep - 1)
    local endsAt = tonumber(string.sub(member, sep + 1))
    if otherSlot ~= slotId and endsAt and endsAt > startTs then
      return cjson.encode({code = 50103})
    end
  end
end

-- 检查 3：余量扣减
local stock = redis.call('GET', stockKey)
if not stock then return cjson.encode({code = 10004}) end
local before = tonumber(stock)
if before <= 0 then return cjson.encode({code = 10005}) end
local after = redis.call('DECR', stockKey)

-- 落一人一时段标记 + 技师时间轴占位（member 带 slotId 以便同 slot 跳过）
redis.call('SADD', userKey, userId)
redis.call('ZADD', timelineKey, startTs, slotId .. '#' .. endTs)

-- 审计 trace：扣减事实与业务动作同事务写入（对账以此为准）
local entry = cjson.encode({
  logType = 1, ts = tonumber(redis.call('TIME')[1]), orderId = orderId,
  traceId = traceId, userId = userId, slotId = slotId,
  beforeQty = before, changeQty = 1, afterQty = after
})
redis.call('HSET', traceKey, traceId, entry)
redis.call('EXPIRE', traceKey, ttl)

return cjson.encode({code = 200, beforeQty = before, deductQty = 1, afterQty = after})
