-- KEYS[1] = execution hash
-- KEYS[2] = nonterminal set
-- ARGV: id,symbol,action,stage,version,created,updated,payload
if redis.call('EXISTS', KEYS[1]) == 1 then
  return 0
end
redis.call('HSET', KEYS[1],
  'execution_id', ARGV[1],
  'symbol', ARGV[2],
  'action', ARGV[3],
  'stage', ARGV[4],
  'version', ARGV[5],
  'created_at_ms', ARGV[6],
  'updated_at_ms', ARGV[7],
  'payload_json', ARGV[8],
  'result_json', '')
redis.call('SADD', KEYS[2], ARGV[1])
return 1
