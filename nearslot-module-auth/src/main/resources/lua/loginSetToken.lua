-- 登录写入 4 键（AT / RT / validVersion / currentVersion）原子执行
-- 实证：直接 SET 覆盖而非先 DEL（防主从复制窗口丢键）
local atKey, rtKey, verKey, curKey = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
local atVal, rtVal, version, ttl = ARGV[1], ARGV[2], ARGV[3], tonumber(ARGV[4])
redis.call('SET', atKey, atVal, 'EX', ttl)
redis.call('SET', rtKey, rtVal, 'EX', ttl)
redis.call('SET', verKey, version, 'EX', ttl)
redis.call('SET', curKey, atVal, 'EX', ttl)
return 200
