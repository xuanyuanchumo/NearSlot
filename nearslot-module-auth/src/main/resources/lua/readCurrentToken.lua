-- 版本守卫：validVersion == oldVersion 才返回当前凭证，防新登录凭证泄露给旧请求
local verKey, curKey = KEYS[1], KEYS[2]
local oldVersion = ARGV[1]
local valid = redis.call('GET', verKey)
if not valid then return 0 end
if valid ~= oldVersion then return 0 end
local cur = redis.call('GET', curKey)
if not cur then return 0 end
return cur
