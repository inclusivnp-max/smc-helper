-- KEYS[1] = risk reservation hash
-- ARGV[1] = reservation_id
-- ARGV[2] = committed amount in scaled integer units
local existing = redis.call('HGET', KEYS[1], ARGV[1])
if not existing then
  return redis.error_reply('RISK_RESERVATION_NOT_FOUND')
end
local now = redis.call('TIME')
local value = cjson.encode({
  amount_units=ARGV[2],
  state='COMMITTED',
  updated_at_ms=tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
})
redis.call('HSET', KEYS[1], ARGV[1], value)
return value
