-- regex 类（对应 luakit common/clib/regex.c）
--   local r = regex{ pattern = "^https?://" }
--   r:match(s) -> boolean ；r.pattern 只读
-- 底层 RE2；PCRE 独有语法（回溯断言等）会在构造时报错。

local object = __lk.object
local N = __luakit

local regex

regex = object.class("regex", {
    props = {
        pattern = { get = function(obj) return object.priv(obj).pattern end },
    },
    methods = {
        match = function(obj, s)
            local p = object.priv(obj)
            if type(s) ~= "string" then
                error("regex:match expects a string", 2)
            end
            return N.regex_match(p.id, s)
        end,
    },
    new = function(props)
        if type(props) ~= "table" or type(props.pattern) ~= "string" then
            error("regex{} requires a 'pattern' string", 3)
        end
        local id, err = N.regex_compile(props.pattern)
        if not id then
            error(("regex: invalid pattern %q: %s"):format(props.pattern, tostring(err)), 3)
        end
        return object.new(regex, { id = id, pattern = props.pattern })
    end,
    gc = function(obj)
        local p = object.priv(obj)
        if p and p.id then
            N.regex_free(p.id)
            p.id = nil
        end
    end,
})

_G.regex = regex
return regex
