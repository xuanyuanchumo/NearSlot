-- 刷新：校验 RT 存在且版本一致；刷新不 bump version
-- 返回 200 成功 / 413 RT 缺失 / 421 RT 与当前用户不匹配 / 422 并发自愈
local rtKey, atKey, verKey, curKey = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
local oldRt, newAt, userId, ttl = ARGV[1], ARGV[2], ARGV[3], tonumber(ARGV[4])
local currentRt = redis.call('GET', rtKey)
if not currentRt then return 413 end
if currentRt ~= oldRt then
  local cur = redis.call('GET', curKey)
  if cur then return 422 end
  return 421
end
redis.call('SET', atKey, newAt, 'EX', ttl)
redis.call('SET', curKey, newAt, 'EX', ttl)
return 200
