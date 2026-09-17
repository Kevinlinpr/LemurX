-- stylesheet 类（对应 luakit clib/stylesheet.c）
--   local ss = stylesheet{ source = "body { ... }" }
--   ss.source 可读写；改 source 会让所有挂了它的 webview 重新注入
-- 挂到页面上是 webview.stylesheets[ss] = true（P1 webview 实现）。

local object = __lk.object

local stylesheet
local all = setmetatable({}, { __mode = "k" }) -- ss -> true，webview 重刷时遍历

stylesheet = object.class("stylesheet", {
    props = {
        source = {
            get = function(obj) return object.priv(obj).source end,
            set = function(obj, v)
                if type(v) ~= "string" then error("stylesheet.source must be a string", 3) end
                local p = object.priv(obj)
                p.source = v
                p.version = (p.version or 0) + 1
                if __lk.on_stylesheet_changed then
                    __lk.on_stylesheet_changed(obj)
                end
            end,
        },
    },
    new = function(props)
        local obj = object.new(stylesheet, { source = "", version = 0, id = nil })
        all[obj] = true
        if props and props.source ~= nil then
            obj.source = props.source
        end
        return obj
    end,
})

__lk.all_stylesheets = all

_G.stylesheet = stylesheet
return stylesheet
