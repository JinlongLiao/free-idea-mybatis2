-- KEYS[1] 为 Stream 键。ARGV 为 XRANGE 起始 ID、前瞻行数、字段响应预算。
-- 每行返回 ID、快照是否完整（1/0）及按原顺序保留的字段与值。
-- Redis 仍需在服务端读取原始消息。预算约束返回给客户端的 RESP2 内容及协议开销。
local rows = redis.call('XRANGE', KEYS[1], ARGV[1], '+', 'COUNT', ARGV[2])
-- 先为外层数组及每行 ID、数组标记和完整状态预留字节，再分配字段预算。
local remaining = tonumber(ARGV[3]) - 8 - 80 * #rows
local result = {}
for _, row in ipairs(rows) do
	local fields = {}
	local complete = 1
	local rowCost = 0
	for offset = 1, #row[2], 2 do
		local field = row[2][offset]
		local value = row[2][offset + 1]
		-- 每对字段各有一个 RESP bulk string，32 字节足以覆盖两者的长度标记及换行符。
		rowCost = rowCost + #field + #value + 32
		if offset > 255 or rowCost > remaining then
			complete = 0
			break
		end
	end
	-- 超限行只返回 ID，不消耗后续消息可用的字段预算。
	if complete == 1 then
		fields = row[2]
		remaining = remaining - rowCost
	end
	result[#result + 1] = {row[1], complete, fields}
end
return result
