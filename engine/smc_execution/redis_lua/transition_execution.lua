-- KEYS[1] = execution hash
-- KEYS[2] = nonterminal set
-- ARGV[1] = execution_id
-- ARGV[2] = expected stage CSV or '*'
-- ARGV[3] = new stage
-- ARGV[4] = patch JSON object
-- ARGV[5] = result JSON or '' to preserve
-- ARGV[6] = terminal ('1'/'0')
-- ARGV[7] = updated_at_ms
if redis.call('EXISTS', KEYS[1]) == 0 then
  return redis.error_reply('EXECUTION_NOT_FOUND')
end
local current = redis.call('HGET', KEYS[1], 'stage')
if ARGV[2] ~= '*' then
  local matched = false
  for stage in string.gmatch(ARGV[2], '([^,]+)') do
    if stage == current then matched = true end
  end
  if not matched then
    return redis.error_reply('STATE_CONFLICT:' .. current)
  end
end
local payload_raw = redis.call('HGET', KEYS[1], 'payload_json') or '{}'
local payload = cjson.decode(payload_raw)
local patch = cjson.decode(ARGV[4])
for key, value in pairs(patch) do payload[key] = value end
local version = tonumber(redis.call('HGET', KEYS[1], 'version') or '0') + 1
redis.call('HSET', KEYS[1],
  'stage', ARGV[3],
  'version', tostring(version),
  'updated_at_ms', ARGV[7],
  'payload_json', cjson.encode(payload))
if ARGV[5] ~= '' then
  redis.call('HSET', KEYS[1], 'result_json', ARGV[5])
end
if ARGV[6] == '1' then
  redis.call('SREM', KEYS[2], ARGV[1])
else
  redis.call('SADD', KEYS[2], ARGV[1])
end
return redis.call('HGETALL', KEYS[1])
