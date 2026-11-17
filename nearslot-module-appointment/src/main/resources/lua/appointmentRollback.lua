-- 回补脚本（ADR-005：反转 before/after 语义）
-- KEYS[1]=slot:stock:{slotId} KEYS[2]=slot:user:{slotId}
-- KEYS[3]=slot:trace:{slotId} KEYS[4]=staff:timeline:{techId}
-- ARGV[1]=userId [2]=slotId [3]=techId [4]=startTs [5]=orderId [6]=traceId
-- ARGV[7]=operate(1=同时移除用户标记) [8]=ttlSeconds [9]=endTs [10]=capacity [11]=nowTs
local stockKey, userKey, traceKey, timelineKey = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
local userId, slotId, techId = ARGV[1], ARGV[2], ARGV[3]
local startTs, orderId, traceId = ARGV[4], ARGV[5], ARGV[6]
local operate, ttl = tonumber(ARGV[7]), tonumber(ARGV[8])
local endTs = ARGV[9]
local capacity = tonumber(ARGV[10])
local nowTs = tonumber(ARGV[11])

-- 同 book 脚本：非确定性命令（TIME）不得出现在写操作之后。
-- 时间戳由服务端传入，脚本本身保持确定性。
local nowSec = math.floor(nowTs / 1000)

-- ============================================================
-- 2026-11-17 审计修复（Blocker×2）：
-- ① 回补用 DEL 删除余量键，而全仓**不存在任何重载路径**，
--    取消或超时回补后该时段永久返回 10004。现改为 INCR + capacity 封顶。
-- ② 余量键缺失时脚本提前 return，后面的 SREM/ZREM 全不执行，
--    导致「一人一时段」标记与技师时间轴占位永久残留。
--    现改为：清理动作**无条件执行**，余量处理放进 if 分支。
-- ============================================================

-- 清理动作先做（与余量键是否存在无关）
if operate == 1 then
  redis.call("SREM", userKey, userId)
end
-- 时间轴 member 结构与 book 一致（"slotId#endsAt"）
redis.call("ZREM", timelineKey, slotId .. "#" .. endTs)

local stock = redis.call("GET", stockKey)
local before, after
if not stock then
  if not capacity or capacity <= 0 then
    return cjson.encode({code = 10004})
  end
  redis.call("SET", stockKey, capacity)
  before = capacity
  after = capacity
else
  before = tonumber(stock)
  local incremented = redis.call("INCR", stockKey)
  if capacity and incremented > capacity then
    redis.call("SET", stockKey, capacity)
    incremented = capacity
  end
  after = incremented
end

local entry = cjson.encode({
  logType = 2, ts = nowSec, orderId = orderId,
  traceId = traceId, userId = userId, slotId = slotId,
  beforeQty = after, changeQty = 1, afterQty = before
})
redis.call("HSET", traceKey, traceId, entry)
redis.call("EXPIRE", traceKey, ttl)

return cjson.encode({code = 200, beforeQty = after, changeQty = 1, afterQty = before})
