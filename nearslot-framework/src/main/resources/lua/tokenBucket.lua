-- 令牌桶双桶原子限流（echo research/07 §5.4）
local ipKey, userKey, capacity = KEYS[1], KEYS[2], tonumber(ARGV[1])
local function take(key)
  local tokens = tonumber(redis.call('hget', key, 'tokens') or capacity)
  if tokens >= 1 then
    redis.call('hset', key, 'tokens', tokens - 1)
    redis.call('expire', key, 120)
    return true
  end
  return false
end
if not take(ipKey) then return 10007 end
if not take(userKey) then return 10008 end
return 0
