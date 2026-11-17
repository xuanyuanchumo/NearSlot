-- 验证码原子消费 GET+DEL（防重复使用）
local key = KEYS[1]
local expected = ARGV[1]
local cached = redis.call('GET', key)
if not cached then return 0 end
redis.call('DEL', key)
if cached ~= expected then return 0 end
return 1
