-- Values are scaled integer micro-USDT units to avoid Lua floating-point drift.
-- KEYS[1] = risk reservation hash
-- ARGV[1] = reservation_id
-- ARGV[2] = requested amount units
-- ARGV[3] = max total risk units
-- ARGV[4] = external open risk units
local existing = redis.call('HGET', KEYS[1], ARGV[1])
if existing then return existing end
local entries = redis.call('HVALS', KEYS[1])
local reserved = 0
local committed = 0
for _, raw in ipairs(entries) do
  local item = cjson.decode(raw)
  local amount = tonumber(item.amount_units)
  if item.state == 'RESERVED' then
    reserved = reserved + amount
  elseif item.state == 'COMMITTED' then
    committed = committed + amount
  end
end
local external = tonumber(ARGV[4])
local effective = committed
if external > effective then effective = external end
effective = effective + reserved
if effective + tonumber(ARGV[2]) > tonumber(ARGV[3]) then return nil end
local now = redis.call('TIME')
local value = cjson.encode({
  amount_units=ARGV[2],
  state='RESERVED',
  updated_at_ms=tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
})
redis.call('HSET', KEYS[1], ARGV[1], value)
return value
