-- Read a bounded composite-key snapshot or delete only when that full snapshot still matches.
-- Arguments for read: mode, max members, max bytes, max Stream field pairs.
-- Arguments for delete: mode, type, member count, snapshot bytes, absolute expiry, then values.
local key = KEYS[1]
local keyType = redis.call('TYPE', key).ok

local function reply(status, count, byteCount, expireAt, payload)
	return {status, keyType, count, byteCount, expireAt, payload or {}}
end

-- Redis 7+ exposes the absolute expiry. Older servers can safely guard persistent keys only.
local function readExpireAt()
	local expireAt = redis.pcall('PEXPIRETIME', key)
	if type(expireAt) == 'table' and expireAt.err then
		if redis.call('PTTL', key) == -1 then return -1, false end
		return nil, true
	end
	return expireAt, false
end

local function readStreamGroupCount()
	local information = redis.pcall('XINFO', 'STREAM', key)
	if type(information) == 'table' and information.err then return nil end
	for index = 1, #information, 2 do
		if information[index] == 'groups' then return tonumber(information[index + 1]) end
	end
	return nil
end

local function supportedCompositeType(valueType)
	return valueType == 'hash' or valueType == 'set' or valueType == 'zset'
		or valueType == 'list' or valueType == 'stream'
end

if ARGV[1] == 'read' then
	if keyType == 'none' then
		return reply(0, 0, 0, -2)
	end
	if not supportedCompositeType(keyType) then
		return reply(1, 0, 0, -2)
	end
	local expireAt, expirationUnsupported = readExpireAt()
	if expirationUnsupported then return reply(5, 0, 0, -2) end
	if keyType == 'stream' then
		local groupCount = readStreamGroupCount()
		if not groupCount then return reply(6, 0, 0, expireAt) end
		if groupCount > 0 then return reply(7, groupCount, 0, expireAt) end
	end

	local maxMembers = tonumber(ARGV[2])
	local maxBytes = tonumber(ARGV[3])
	local maxStreamFields = tonumber(ARGV[4])
	local function overLimit(count, byteCount)
		return count > maxMembers or byteCount > maxBytes
	end

	if keyType == 'hash' then
		local values = {}
		local seen = {}
		local count = 0
		local byteCount = 0
		local cursor = '0'
		local rounds = 0
		repeat
			local page = redis.call('HSCAN', key, cursor, 'COUNT', '64')
			cursor = page[1]
			rounds = rounds + 1
			for index = 1, #page[2], 2 do
				local field = page[2][index]
				local value = page[2][index + 1]
				local oldValue = seen[field]
				local nextCount = count
				local nextBytes = byteCount
				if oldValue == nil then
					nextCount = nextCount + 1
				else
					nextBytes = nextBytes - string.len(field) - string.len(oldValue)
				end
				nextBytes = nextBytes + string.len(field) + string.len(value)
				if overLimit(nextCount, nextBytes) then
					return reply(2, nextCount, nextBytes, expireAt)
				end
				seen[field] = value
				count = nextCount
				byteCount = nextBytes
			end
			if rounds > 64 and cursor ~= '0' then
				return reply(4, count, byteCount, expireAt)
			end
		until cursor == '0'
		for field, value in pairs(seen) do
			table.insert(values, field)
			table.insert(values, value)
		end
		return reply(3, count, byteCount, expireAt, values)
	end

	if keyType == 'set' then
		local values = {}
		local seen = {}
		local count = 0
		local byteCount = 0
		local cursor = '0'
		local rounds = 0
		repeat
			local page = redis.call('SSCAN', key, cursor, 'COUNT', '64')
			cursor = page[1]
			rounds = rounds + 1
			for _, member in ipairs(page[2]) do
				if not seen[member] then
					local nextCount = count + 1
					local nextBytes = byteCount + string.len(member)
					if overLimit(nextCount, nextBytes) then
						return reply(2, nextCount, nextBytes, expireAt)
					end
					seen[member] = true
					count = nextCount
					byteCount = nextBytes
				end
			end
			if rounds > 64 and cursor ~= '0' then
				return reply(4, count, byteCount, expireAt)
			end
		until cursor == '0'
		for member in pairs(seen) do
			table.insert(values, member)
		end
		return reply(3, count, byteCount, expireAt, values)
	end

	if keyType == 'zset' then
		local count = redis.call('ZCARD', key)
		if count > maxMembers then
			return reply(2, count, 0, expireAt)
		end
		local values = {}
		local byteCount = 0
		for index = 0, count - 1 do
			local entry = redis.call('ZRANGE', key, index, index, 'WITHSCORES')
			if #entry ~= 2 then
				return reply(4, count, byteCount, expireAt)
			end
			local nextBytes = byteCount + string.len(entry[1]) + string.len(entry[2])
			if overLimit(index + 1, nextBytes) then
				return reply(2, index + 1, nextBytes, expireAt)
			end
			table.insert(values, entry[1])
			table.insert(values, entry[2])
			byteCount = nextBytes
		end
		return reply(3, count, byteCount, expireAt, values)
	end

	if keyType == 'list' then
		local count = redis.call('LLEN', key)
		if count > maxMembers then
			return reply(2, count, 0, expireAt)
		end
		local values = {}
		local byteCount = 0
		for index = 0, count - 1 do
			local value = redis.call('LINDEX', key, index)
			if not value then
				return reply(4, count, byteCount, expireAt)
			end
			local nextBytes = byteCount + string.len(value)
			if overLimit(index + 1, nextBytes) then
				return reply(2, index + 1, nextBytes, expireAt)
			end
			table.insert(values, value)
			byteCount = nextBytes
		end
		return reply(3, count, byteCount, expireAt, values)
	end

	local count = redis.call('XLEN', key)
	if count > maxMembers then
		return reply(2, count, 0, expireAt)
	end
	local values = {}
	local byteCount = 0
	local fieldPairs = 0
	local start = '-'
	for index = 1, count do
		local rows = redis.call('XRANGE', key, start, '+', 'COUNT', 1)
		if #rows ~= 1 then
			return reply(4, index - 1, byteCount, expireAt)
		end
		local entry = rows[1]
		local fields = entry[2]
		fieldPairs = fieldPairs + (#fields / 2)
		if fieldPairs > maxStreamFields then
			return reply(2, index, byteCount, expireAt)
		end
		local nextBytes = byteCount + string.len(entry[1])
		for fieldIndex = 1, #fields do
			nextBytes = nextBytes + string.len(fields[fieldIndex])
		end
		if overLimit(index, nextBytes) then
			return reply(2, index, nextBytes, expireAt)
		end
		local row = {entry[1], #fields / 2}
		for fieldIndex = 1, #fields do
			table.insert(row, fields[fieldIndex])
		end
		table.insert(values, row)
		byteCount = nextBytes
		start = '(' .. entry[1]
	end
	return reply(3, count, byteCount, expireAt, values)
end

if ARGV[1] == 'delete' then
	local expectedType = ARGV[2]
	local expectedCount = tonumber(ARGV[3])
	local expectedBytes = tonumber(ARGV[4])
	local expectedExpireAt = tonumber(ARGV[5])
	if keyType ~= expectedType or not supportedCompositeType(expectedType)
		or not expectedCount or not expectedBytes or not expectedExpireAt
		or expectedCount < 0 or expectedBytes < 0 or expectedCount > 256 or expectedBytes > 65536 then
		return 0
	end
	local currentExpireAt, expirationUnsupported = readExpireAt()
	if expirationUnsupported or currentExpireAt ~= expectedExpireAt then return 0 end
	if expectedType == 'stream' then
		local groupCount = readStreamGroupCount()
		if not groupCount or groupCount > 0 then return 0 end
	end
	local argumentIndex = 6
	local expectedStreamFieldPairs = 0
	if expectedType == 'hash' then
		if redis.call('HLEN', key) ~= expectedCount then return 0 end
		local seen = {}
		for _ = 1, expectedCount do
			local field = ARGV[argumentIndex]
			local expected = ARGV[argumentIndex + 1]
			if seen[field] then return 0 end
			seen[field] = true
			local current = redis.call('HGET', key, field)
			if not current or current ~= expected then return 0 end
			argumentIndex = argumentIndex + 2
		end
	elseif expectedType == 'set' then
		if redis.call('SCARD', key) ~= expectedCount then return 0 end
		local seen = {}
		for _ = 1, expectedCount do
			local member = ARGV[argumentIndex]
			if seen[member] or redis.call('SISMEMBER', key, member) == 0 then return 0 end
			seen[member] = true
			argumentIndex = argumentIndex + 1
		end
	elseif expectedType == 'zset' then
		if redis.call('ZCARD', key) ~= expectedCount then return 0 end
		local seen = {}
		for _ = 1, expectedCount do
			local member = ARGV[argumentIndex]
			local expectedScore = tonumber(ARGV[argumentIndex + 1])
			local currentScore = redis.call('ZSCORE', key, member)
			if seen[member] or not currentScore or tonumber(currentScore) ~= expectedScore then return 0 end
			seen[member] = true
			argumentIndex = argumentIndex + 2
		end
	elseif expectedType == 'list' then
		if redis.call('LLEN', key) ~= expectedCount then return 0 end
		for index = 0, expectedCount - 1 do
			local current = redis.call('LINDEX', key, index)
			if not current or current ~= ARGV[argumentIndex] then return 0 end
			argumentIndex = argumentIndex + 1
		end
	else
		if redis.call('XLEN', key) ~= expectedCount then return 0 end
		for _ = 1, expectedCount do
			local id = ARGV[argumentIndex]
			local fieldCount = tonumber(ARGV[argumentIndex + 1])
			argumentIndex = argumentIndex + 2
			if not fieldCount or fieldCount < 1 or fieldCount > 512 then return 0 end
			expectedStreamFieldPairs = expectedStreamFieldPairs + fieldCount
			if expectedStreamFieldPairs > 512 then return 0 end
			local rows = redis.call('XRANGE', key, id, id)
			if #rows ~= 1 then return 0 end
			local fields = rows[1][2]
			if #fields ~= fieldCount * 2 then return 0 end
			for fieldIndex = 1, #fields do
				if fields[fieldIndex] ~= ARGV[argumentIndex] then return 0 end
				argumentIndex = argumentIndex + 1
			end
		end
	end
	if argumentIndex ~= #ARGV + 1 then return 0 end
	return redis.call('DEL', key)
end

return redis.error_reply('Unsupported Redis deletion snapshot operation')
