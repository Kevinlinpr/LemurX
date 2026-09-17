-- LemurX · luakit-compatible library · lousy.load
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.load" module API. No luakit code is used.
--
-- 读取整个文件为字符串：lousy.load(path[, memorize]) -> string | nil, err
-- 相对路径依次在 config / resources / data / install 目录中查找。
-- memorize=true 时结果按最终路径缓存（适合模板、CSS 等只读资源）。

local util = require("lousy.util")

local cache = {}

local function resolve(path)
    if util.is_absolute(path) then return path end
    return util.find_config(path, true)
        or util.find_resource(path, true)
        or util.find_data(path, true)
        or util.find_install(path, true)
        or path
end

local function load_file(path, memorize)
    if type(path) ~= "string" or path == "" then
        return nil, "lousy.load: path must be a non-empty string"
    end
    local full = resolve(path)
    if memorize and cache[full] ~= nil then return cache[full] end
    local data, err = util.read_file(full)
    if data == nil then return nil, err end
    if memorize then cache[full] = data end
    return data
end

local M = setmetatable({}, {
    __call = function(_, path, memorize) return load_file(path, memorize) end,
})

M.load = load_file
M.resolve = resolve

function M.clear_cache(path)
    if path then cache[resolve(path)] = nil else cache = {} end
end

return M
