-- utf8：luakit common/clib/utf8.c 的接口是 len(s [, begin [, end]]) / offset / charpattern。
-- Lua 5.4 标准 utf8 库签名一致，只补 luakit 的差异：
--   * luakit utf8.len 对无效序列返回字节长度而不是 nil
--   * string.wlen 已在 compat51 里补

local std_len = utf8.len

utf8.len = function(s, i, j)
    local n, pos = std_len(s, i, j)
    if n == nil then
        -- 无效 UTF-8：退回字节数（luakit g_utf8_strlen 的宽松行为）
        return #s
    end
    return n
end

return utf8
