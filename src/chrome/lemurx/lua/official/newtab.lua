-- @name 新标签页
-- @description Momentum + Todoist + Earth View 替代：每日壁纸（Bing / Google Earth View / 自定义）、时钟问候、今日专注、待办清单、快捷方式、天气、每日一句，接管"+"新标签页
-- @version 1.0.0
-- @icon 🌄
-- @category 效率
-- @replaces Momentum · Todoist for Chrome · Earth View from Google Earth · Infinity 新标签页 · Tabliss
-- @page lemurx://newtab/
--
-- 结构：
--   lemurx://newtab/          页面本体（纯 HTML/JS，数据全走 lemurx://newtab/api/*）
--   lemurx://newtab/settings  设置
--   接管方式：lemurx.chrome.setNewTabUrl("lemurx://newtab/") —— "+" 和菜单"新标签页"直接开这个 URL
--
-- 数据：lua/official/newtab/{todos,links,bg,weather}.json

local lx = require("lx")
local util = lx.util
local json = lx.json

local ID = "newtab"
local URL = "lemurx://newtab/"
local data = lx.data(ID)

local S
S = lx.register({
    id = ID, name = "新标签页", version = "1.0.0", icon = "🌄",
    description = "每日壁纸、时钟问候、今日专注、待办、快捷方式、天气、每日一句。替代 Momentum / Todoist / Earth View。",
    replaces = "Momentum · Todoist for Chrome · Earth View from Google Earth · Infinity · Tabliss",
    settings = {
        enabled = true,
        override = true,
        name = "",
        bg = "bing",                -- bing | earth | custom | color
        bg_custom = "",
        bg_color = "#1c2833",
        bg_dim = 25,
        search = "google",
        show_clock = true, clock_24 = true, show_seconds = false,
        show_focus = true, show_todo = true, show_links = true, show_weather = true, show_quote = true,
        weather_city = "",
        weather_lat = nil, weather_lon = nil,
        links_auto = true,
    },
    schema = {
        { key = "enabled", type = "bool", label = "启用", section = "总开关" },
        { key = "override", type = "bool", label = "接管新标签页", desc = "关掉后 \"+\" 回到原生新标签页，本页仍可从菜单打开" },
        { key = "name", type = "string", label = "怎么称呼你", section = "个性化", placeholder = "留空不显示" },
        { key = "bg", type = "select", label = "壁纸来源", options = { { "bing", "Bing 每日壁纸" }, { "earth", "Google Earth View 卫星图" }, { "custom", "自定义图片 URL" }, { "color", "纯色" } } },
        { key = "bg_custom", type = "string", label = "自定义图片 URL", placeholder = "https://…/wallpaper.jpg" },
        { key = "bg_color", type = "string", label = "纯色背景", placeholder = "#1c2833" },
        { key = "bg_dim", type = "number", label = "壁纸压暗 %", min = 0, max = 70, desc = "0～70，字看不清就调高" },
        { key = "search", type = "select", label = "搜索引擎", options = { { "google", "Google" }, { "bing", "Bing" }, { "baidu", "百度" }, { "ddg", "DuckDuckGo" }, { "sogou", "搜狗" } } },
        { key = "show_clock", type = "bool", label = "时钟", section = "模块" },
        { key = "clock_24", type = "bool", label = "24 小时制" },
        { key = "show_seconds", type = "bool", label = "显示秒" },
        { key = "show_focus", type = "bool", label = "今日专注" },
        { key = "show_todo", type = "bool", label = "待办清单" },
        { key = "show_links", type = "bool", label = "快捷方式" },
        { key = "links_auto", type = "bool", label = "自动补充常去站点", desc = "从历史记录里挑最常去的补满快捷方式" },
        { key = "show_weather", type = "bool", label = "天气", desc = "数据来自 open-meteo.com，不需要密钥" },
        { key = "weather_city", type = "string", label = "城市", placeholder = "留空自动按 IP 定位；或输入 上海 / Tokyo" },
        { key = "show_quote", type = "bool", label = "每日一句" },
    },
    menu = {
        { id = "open", title = "新标签页", url = URL },
    },
    api = {},
})
local settings = S.settings

-- ===== 状态 =====
local todos = { items = {}, focus = "", focus_date = "" }
local links = { items = {} }
local bg = nil            -- { engine, date, data_uri | url, title, copyright, link }
local weather = nil       -- { at, city, temp, code, hi, lo, lat, lon }
local bg_fetching = false
local bg_waiters = {}

local function save_todos() data:write_json("todos.json", todos) end
local function save_links() data:write_json("links.json", links) end
local function new_id() return ("%x%03x"):format(os.time(), math.random(0, 0xfff)) end

-- ===== 接管 =====
local function apply_override()
    local on = settings:get("enabled") and settings:get("override")
    pcall(lemurx.chrome.setNewTabUrl, on and URL or nil)
end

-- ===== 壁纸 =====
local EARTH_IDS = { 1003, 1004, 1006, 1013, 1017, 1018, 1019, 1020, 1027, 1028, 1029, 1030, 1032, 1036, 1039, 1041, 1043, 1046, 1050, 1051, 1053, 1057, 1060, 1062, 1064, 1065, 1067, 1071, 1075, 1077, 1078, 1080, 1081, 1082, 1084, 1088, 1089, 1090, 1094, 1097, 1099, 1101, 1104, 1106, 1109, 1112, 1115, 1117, 1119, 1122, 1126, 1128, 1131, 1136, 1140, 1143, 1147, 1152, 1156, 1160, 1163, 1167, 1172, 1176, 1180, 1185, 1190, 1194, 1199, 1204, 1208, 1213, 1218, 1222, 1226, 1231, 1235, 1240, 1245, 1249, 1254, 1259, 1263, 1268, 1272, 1277, 1282, 1286, 1291, 1296, 1300, 1305, 1310, 1314, 1319, 1324, 1329, 1333, 1338, 1343, 1347, 1352, 1357, 1362, 1366, 1371, 1376, 1380, 1385, 1390, 1395, 1400, 1404, 1409, 1414, 1419, 1423, 1428, 1433, 1438, 1442, 1447, 1452, 1457, 1462, 1466, 1471, 1476, 1481, 1486, 1490, 1495, 1500, 1505, 1510, 1514, 1519, 1524, 1529, 1534, 1538, 1543, 1548, 1553, 1558, 1562, 1567, 1572, 1577, 1582, 1586, 1591, 1596, 1601, 1606, 1610, 1615, 1620, 1625, 1630, 1634, 1639, 1644, 1649, 1654, 1658, 1663, 1668, 1673, 1678, 1682, 1687, 1692, 1697, 1702, 1706, 1711, 1716, 1721, 1726, 1730, 1735, 1740, 1745, 1750, 1754, 1759, 1764, 1769, 1774, 1778, 1783, 1788, 1793, 1798, 1802, 1807, 1812, 1817, 1822, 1826, 1831, 1836, 1841, 1846, 1850, 1855, 1860, 1865, 1870, 1874, 1879, 1884, 1889, 1894, 1898, 1903, 1908, 1913, 1918, 1922, 1927, 1932, 1937, 1942, 1946, 1951, 1956, 1961, 1966, 1970, 1975, 1980, 1985, 1990, 1994, 1999, 2004, 2009, 2014, 2018, 2023, 2028, 2033, 2038, 2042, 2047, 2052, 2057, 2062, 2066, 2071, 2076, 2081, 2086, 2090, 2095, 2100, 2105, 2110, 2114, 2119, 2124, 2129, 2134, 2138, 2143, 2148, 2153, 2158, 2162, 2167, 2172, 2177, 2182, 2186, 2191, 2196, 2201, 2206, 2210, 2215, 2220, 2225, 2230, 2234, 2239, 2244, 2249, 2254, 2258, 2263, 2268, 2273, 2278, 2282, 2287, 2292, 2297, 2302, 2306, 2311, 2316, 2321, 2326, 2330, 2335, 2340, 2345, 2350, 2354, 2359, 2364, 2369, 2374, 2378, 2383, 2388, 2393, 2398, 2402, 2407, 2412, 2417, 2422, 2426, 2431, 2436, 2441, 2446, 2450, 2455, 2460, 2465, 2470, 2474, 2479, 2484, 2489, 2494, 2498, 2503, 2508, 2513, 2518, 2522, 2527, 2532, 2537, 2542, 2546, 2551, 2556, 2561, 2566, 2570, 2575, 2580, 2585, 2590, 2594, 2599 }

local function today() return os.date("%Y-%m-%d") end

local function set_bg(b)
    bg = b
    data:write_json("bg.json", b)
end

local function fetch_bing(done)
    lx.fetch("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN", { timeout = 15000 }, function(r)
        if not (r.ok and r.body) then return done(nil, "bing: " .. tostring(r.error or r.status)) end
        local t = json.decode(r.body)
        local img = type(t) == "table" and type(t.images) == "table" and t.images[1]
        if not img then return done(nil, "bing: bad json") end
        local url = "https://www.bing.com" .. (img.url or "")
        lx.fetch(url, { timeout = 30000, binary = true }, function(ir)
            if not (ir.ok and ir.body) then return done(nil, "bing image: " .. tostring(ir.error or ir.status)) end
            local mime = "image/jpeg"
            local data_uri = ir.base64 and ("data:" .. mime .. ";base64," .. ir.body) or nil
            done({ engine = "bing", date = today(), data_uri = data_uri, url = not data_uri and url or nil,
                title = img.title or "", copyright = img.copyright or "", link = img.copyrightlink or "" })
        end)
    end)
end

local function fetch_earth(done, attempt)
    attempt = attempt or 1
    local id = EARTH_IDS[math.random(#EARTH_IDS)]
    lx.fetch("https://www.gstatic.com/prettyearth/assets/data/v3/" .. id .. ".json", { timeout = 30000 }, function(r)
        if not (r.ok and r.body) then
            if attempt < 3 then return fetch_earth(done, attempt + 1) end
            return done(nil, "earth: " .. tostring(r.error or r.status))
        end
        local t = json.decode(r.body)
        if type(t) ~= "table" or type(t.dataUri) ~= "string" then
            if attempt < 3 then return fetch_earth(done, attempt + 1) end
            return done(nil, "earth: bad json")
        end
        local place = {}
        local g = type(t.geocode) == "table" and t.geocode or {}
        for _, k in ipairs({ "locality", "administrative_area_level_1", "country" }) do
            if type(g[k]) == "string" and g[k] ~= "" then place[#place + 1] = g[k] end
        end
        done({ engine = "earth", date = today(), data_uri = t.dataUri, title = #place > 0 and table.concat(place, ", ") or (t.slug or "Earth View"),
            copyright = t.attribution or "Google Earth", link = "https://earth.google.com/web/@" .. tostring(t.lat or 0) .. "," .. tostring(t.lng or 0) .. ",0a,10000d,35y,0h,0t,0r",
            lat = t.lat, lng = t.lng })
    end)
end

local function refresh_bg(force, cb)
    local engine = settings:get("bg")
    if engine == "color" then
        set_bg({ engine = "color", date = today() })
        if cb then cb(bg) end
        return
    elseif engine == "custom" then
        set_bg({ engine = "custom", date = today(), url = settings:get("bg_custom") })
        if cb then cb(bg) end
        return
    end
    if not force and bg and bg.engine == engine and bg.date == today() and (bg.data_uri or bg.url) then
        if cb then cb(bg) end
        return
    end
    -- 正在拉：排队，拉完一起回（新标签页第一次打开不会拿到空壁纸）
    if cb then bg_waiters[#bg_waiters + 1] = cb end
    if bg_fetching then return end
    bg_fetching = true
    local function finish()
        bg_fetching = false
        local w = bg_waiters
        bg_waiters = {}
        for _, f in ipairs(w) do f(bg) end
    end
    local fetcher = engine == "earth" and fetch_earth or fetch_bing
    fetcher(function(b, err)
        if b then
            set_bg(b)
        else
            lx.log("newtab: wallpaper failed: %s", tostring(err))
            if engine == "earth" and not (bg and bg.data_uri) then
                -- Earth View 拿不到就退到 Bing
                return fetch_bing(function(b2) if b2 then set_bg(b2) end finish() end)
            end
        end
        finish()
    end)
end

-- ===== 天气 =====
local WMO = {
    [0] = { "晴", "☀️" }, [1] = { "大部晴朗", "🌤" }, [2] = { "多云", "⛅" }, [3] = { "阴", "☁️" },
    [45] = { "雾", "🌫" }, [48] = { "雾", "🌫" }, [51] = { "小毛雨", "🌦" }, [53] = { "毛雨", "🌦" }, [55] = { "大毛雨", "🌧" },
    [56] = { "冻雨", "🌧" }, [57] = { "冻雨", "🌧" }, [61] = { "小雨", "🌧" }, [63] = { "中雨", "🌧" }, [65] = { "大雨", "🌧" },
    [66] = { "冻雨", "🌧" }, [67] = { "冻雨", "🌧" }, [71] = { "小雪", "🌨" }, [73] = { "中雪", "🌨" }, [75] = { "大雪", "❄️" },
    [77] = { "雪粒", "🌨" }, [80] = { "阵雨", "🌦" }, [81] = { "阵雨", "🌧" }, [82] = { "强阵雨", "⛈" }, [85] = { "阵雪", "🌨" },
    [86] = { "阵雪", "❄️" }, [95] = { "雷雨", "⛈" }, [96] = { "雷雨冰雹", "⛈" }, [99] = { "雷雨冰雹", "⛈" },
}
local weather_fetching = false
local weather_waiters = {}

local function fetch_forecast(lat, lon, city, done)
    local u = ("https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,relative_humidity_2m&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"):format(lat, lon)
    lx.fetch(u, { timeout = 15000 }, function(r)
        if not (r.ok and r.body) then return done(nil, "forecast: " .. tostring(r.error or r.status)) end
        local t = json.decode(r.body)
        if type(t) ~= "table" or type(t.current) ~= "table" then return done(nil, "forecast: bad json") end
        local code = tonumber(t.current.weather_code) or 0
        local d = WMO[code] or { "未知", "🌡" }
        done({ at = os.time(), city = city, lat = lat, lon = lon, temp = t.current.temperature_2m, humidity = t.current.relative_humidity_2m,
            code = code, text = d[1], icon = d[2],
            hi = t.daily and t.daily.temperature_2m_max and t.daily.temperature_2m_max[1], lo = t.daily and t.daily.temperature_2m_min and t.daily.temperature_2m_min[1] })
    end)
end

local function refresh_weather(force, cb)
    if not settings:get("show_weather") then if cb then cb(nil) end return end
    if not force and weather and (os.time() - (weather.at or 0)) < 1800 and weather.city_key == settings:get("weather_city") then
        if cb then cb(weather) end
        return
    end
    if cb then weather_waiters[#weather_waiters + 1] = cb end
    if weather_fetching then return end
    weather_fetching = true
    local function finish(w, err)
        weather_fetching = false
        if w then
            w.city_key = settings:get("weather_city")
            weather = w
            data:write_json("weather.json", w)
        else
            lx.log("newtab: weather failed: %s", tostring(err))
        end
        local ws = weather_waiters
        weather_waiters = {}
        for _, f in ipairs(ws) do f(weather) end
    end
    local city = util.trim(settings:get("weather_city") or "")
    local lat, lon = tonumber(settings:get("weather_lat")), tonumber(settings:get("weather_lon"))
    if city ~= "" then
        lx.fetch("https://geocoding-api.open-meteo.com/v1/search?name=" .. util.url.encode(city) .. "&count=1&language=zh&format=json", { timeout = 15000 }, function(r)
            local t = r.ok and r.body and json.decode(r.body)
            local hit = type(t) == "table" and type(t.results) == "table" and t.results[1]
            if not hit then return finish(nil, "geocode: no result for " .. city) end
            fetch_forecast(hit.latitude, hit.longitude, hit.name or city, finish)
        end)
    elseif lat and lon then
        fetch_forecast(lat, lon, "当前位置", finish)
    else
        -- IP 定位
        lx.fetch("https://ipapi.co/json/", { timeout = 15000 }, function(r)
            local t = r.ok and r.body and json.decode(r.body)
            if type(t) ~= "table" or not tonumber(t.latitude) then return finish(nil, "ip geolocation failed") end
            settings:set("weather_lat", tonumber(t.latitude), true)
            settings:set("weather_lon", tonumber(t.longitude), true)
            fetch_forecast(tonumber(t.latitude), tonumber(t.longitude), t.city or "当前位置", finish)
        end)
    end
end

-- ===== 每日一句 =====
local QUOTES = {
    { "不积跬步，无以至千里。", "荀子" }, { "路漫漫其修远兮，吾将上下而求索。", "屈原" }, { "天行健，君子以自强不息。", "《周易》" },
    { "知之者不如好之者，好之者不如乐之者。", "《论语》" }, { "千里之行，始于足下。", "老子" }, { "少年易老学难成，一寸光阴不可轻。", "朱熹" },
    { "The best way to predict the future is to invent it.", "Alan Kay" }, { "Simplicity is the ultimate sophistication.", "Leonardo da Vinci" },
    { "Stay hungry, stay foolish.", "Stewart Brand" }, { "What we think, we become.", "Buddha" }, { "Well begun is half done.", "Aristotle" },
    { "It always seems impossible until it's done.", "Nelson Mandela" }, { "Talk is cheap. Show me the code.", "Linus Torvalds" },
    { "Make it work, make it right, make it fast.", "Kent Beck" }, { "The only way to do great work is to love what you do.", "Steve Jobs" },
    { "业精于勤，荒于嬉；行成于思，毁于随。", "韩愈" }, { "纸上得来终觉浅，绝知此事要躬行。", "陆游" }, { "博观而约取，厚积而薄发。", "苏轼" },
    { "Focus is about saying no.", "Steve Jobs" }, { "Done is better than perfect.", "Sheryl Sandberg" }, { "生活不是等待暴风雨过去，而是学会在雨中跳舞。", "" },
    { "Programs must be written for people to read, and only incidentally for machines to execute.", "Abelson & Sussman" },
    { "First, solve the problem. Then, write the code.", "John Johnson" }, { "行远必自迩，登高必自卑。", "《礼记》" }, { "日拱一卒，功不唐捐。", "" },
}
local function quote_of_day()
    local d = tonumber(os.date("%j")) + tonumber(os.date("%Y")) * 7
    return QUOTES[(d % #QUOTES) + 1]
end

-- ===== 快捷方式 =====
local function auto_links(n)
    if not settings:get("links_auto") then return {} end
    local ok, hist = pcall(lemurx.history.query, "")
    if not ok or type(hist) ~= "table" then return {} end
    local by_host = {}
    for _, h in ipairs(hist) do
        local host = h.domain or util.host_of(h.url or "")
        if host ~= "" and host:find(".", 1, true) and (h.url or ""):match("^https?://") then
            local e = by_host[host]
            if not e then
                e = { host = host, n = 0, title = h.title or host, url = "https://" .. host .. "/" }
                by_host[host] = e
            end
            e.n = e.n + 1
        end
    end
    local list = {}
    local have = {}
    for _, l in ipairs(links.items) do have[util.host_of(l.url)] = true end
    for host, e in pairs(by_host) do
        if not have[host] and not (links.hidden and links.hidden[host]) then list[#list + 1] = e end
    end
    table.sort(list, function(a, b) return a.n > b.n end)
    local out = {}
    for i = 1, math.min(n, #list) do
        out[#out + 1] = { id = "auto:" .. list[i].host, url = list[i].url, title = list[i].host:gsub("^www%.", ""), auto = true }
    end
    return out
end

-- ===== API =====
local function greeting()
    local h = tonumber(os.date("%H"))
    local g = h < 5 and "夜深了" or h < 9 and "早上好" or h < 12 and "上午好" or h < 14 and "中午好" or h < 18 and "下午好" or h < 22 and "晚上好" or "夜深了"
    local name = util.trim(settings:get("name") or "")
    return name ~= "" and (g .. "，" .. name) or g
end

S.api["state"] = function()
    if todos.focus_date ~= today() then
        -- 新的一天：昨天的专注归档
        if todos.focus ~= "" then todos.focus_history = todos.focus_history or {} table.insert(todos.focus_history, 1, { date = todos.focus_date, text = todos.focus, done = todos.focus_done }) if #todos.focus_history > 30 then table.remove(todos.focus_history) end end
        todos.focus, todos.focus_done, todos.focus_date = "", false, today()
        save_todos()
    end
    local q = quote_of_day()
    local all_links = {}
    for _, l in ipairs(links.items) do all_links[#all_links + 1] = l end
    for _, l in ipairs(auto_links(math.max(0, 8 - #all_links))) do all_links[#all_links + 1] = l end
    local cfg = settings:all()
    cfg.weather_lat, cfg.weather_lon = nil, nil
    return {
        greeting = greeting(), settings = cfg,
        focus = { text = todos.focus, done = todos.focus_done or false },
        todos = todos.items, links = all_links,
        weather = settings:get("show_weather") and weather or nil,
        quote = settings:get("show_quote") and { text = q[1], by = q[2] } or nil,
        bg = bg and { engine = bg.engine, title = bg.title, copyright = bg.copyright, link = bg.link, has_image = (bg.data_uri or bg.url) ~= nil, url = bg.url, color = settings:get("bg_color"), dim = settings:get("bg_dim") } or { engine = settings:get("bg"), color = settings:get("bg_color"), dim = settings:get("bg_dim") },
    }
end
S.api["bg"] = function(args, reply)
    refresh_bg(args.force == true, function(b)
        reply({ engine = b and b.engine, data_uri = b and b.data_uri, url = b and b.url, title = b and b.title, copyright = b and b.copyright, link = b and b.link })
    end)
    return "async"
end
S.api["bg.next"] = function(args, reply)
    refresh_bg(true, function(b) reply({ ok = true, title = b and b.title, reload = true }) end)
    return "async"
end
S.api["weather"] = function(args, reply)
    refresh_weather(args.force == true, function(w) reply({ weather = w }) end)
    return "async"
end
S.api["focus.set"] = function(args)
    todos.focus = util.trim(tostring(args.text or ""))
    todos.focus_done = false
    todos.focus_date = today()
    save_todos()
    return { ok = true }
end
S.api["focus.done"] = function(args)
    todos.focus_done = args.done and true or false
    save_todos()
    return { ok = true }
end
S.api["todo.add"] = function(args)
    local text = util.trim(tostring(args.text or ""))
    if text == "" then return nil, "空的" end
    local item = { id = new_id(), text = text, done = false, created = os.time(), due = args.due }
    table.insert(todos.items, 1, item)
    save_todos()
    return { ok = true, item = item }
end
S.api["todo.update"] = function(args)
    for _, it in ipairs(todos.items) do
        if it.id == args.id then
            if args.done ~= nil then it.done = args.done and true or false it.done_at = it.done and os.time() or nil end
            if args.text ~= nil then it.text = util.trim(tostring(args.text)) end
            if args.due ~= nil then it.due = args.due ~= "" and args.due or nil end
            save_todos()
            return { ok = true }
        end
    end
    return nil, "不存在"
end
S.api["todo.remove"] = function(args)
    for i, it in ipairs(todos.items) do
        if it.id == args.id then table.remove(todos.items, i) save_todos() return { ok = true } end
    end
    return nil, "不存在"
end
S.api["todo.clear_done"] = function()
    local keep = {}
    for _, it in ipairs(todos.items) do if not it.done then keep[#keep + 1] = it end end
    todos.items = keep
    save_todos()
    return { ok = true }
end
S.api["link.add"] = function(args)
    local u = util.trim(tostring(args.url or ""))
    if u == "" then return nil, "URL 空" end
    if not u:match("^%a+://") then u = "https://" .. u end
    local title = util.trim(tostring(args.title or ""))
    if title == "" then title = util.host_of(u):gsub("^www%.", "") end
    links.items[#links.items + 1] = { id = new_id(), url = u, title = title }
    save_links()
    return { ok = true }
end
S.api["link.remove"] = function(args)
    for i, l in ipairs(links.items) do
        if l.id == args.id then table.remove(links.items, i) save_links() return { ok = true } end
    end
    if type(args.id) == "string" and args.id:match("^auto:") then
        -- 固定成手动项再删 = 屏蔽：记到 hidden
        links.hidden = links.hidden or {}
        links.hidden[args.id:sub(6)] = true
        save_links()
        return { ok = true }
    end
    return nil, "不存在"
end
S.api["link.pin"] = function(args)
    -- 把自动项固定
    local u = tostring(args.url or "")
    links.items[#links.items + 1] = { id = new_id(), url = u, title = tostring(args.title or util.host_of(u)) }
    save_links()
    return { ok = true }
end
S.api["link.move"] = function(args)
    local from, to = tonumber(args.from), tonumber(args.to)
    if not (from and to and links.items[from] and to >= 1 and to <= #links.items) then return nil, "参数错误" end
    local it = table.remove(links.items, from)
    table.insert(links.items, to, it)
    save_links()
    return { ok = true }
end

-- ===== 页面 =====
local SEARCH = {
    google = "https://www.google.com/search?q=", bing = "https://www.bing.com/search?q=", baidu = "https://www.baidu.com/s?wd=",
    ddg = "https://duckduckgo.com/?q=", sogou = "https://www.sogou.com/web?query=",
}

S.page = function(ctx)
    local search_url = SEARCH[settings:get("search")] or SEARCH.google
    local html = [[<!doctype html><html lang="zh"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><title>新标签页</title>
<style>
:root{--fg:#fff;--dim:rgba(0,0,0,.25)}
*{box-sizing:border-box}html,body{height:100%;margin:0}
body{font:16px/1.4 -apple-system,Roboto,"PingFang SC","Noto Sans CJK SC",sans-serif;color:var(--fg);background:#1c2833;overflow:hidden;-webkit-tap-highlight-color:transparent}
#bg{position:fixed;inset:0;background-size:cover;background-position:center;transition:opacity .8s;opacity:0}#bg.on{opacity:1}
#dim{position:fixed;inset:0;background:var(--dim)}
#top{position:fixed;top:0;left:0;right:0;padding:calc(env(safe-area-inset-top) + 12px) 16px 0;display:flex;justify-content:space-between;align-items:flex-start;font-size:14px;text-shadow:0 1px 6px rgba(0,0,0,.5)}
#weather{display:flex;align-items:center;gap:8px}#weather .t{font-size:22px;font-weight:600}#weather small{opacity:.85;font-size:12px;display:block}
#gear{width:36px;height:36px;border-radius:18px;background:rgba(0,0,0,.25);display:flex;align-items:center;justify-content:center;text-decoration:none;color:#fff;font-size:18px}
main{position:fixed;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:0 24px;text-shadow:0 2px 12px rgba(0,0,0,.6)}
#clock{font-size:clamp(56px,18vw,96px);font-weight:200;letter-spacing:-2px;line-height:1}#clock small{font-size:.35em;font-weight:300;letter-spacing:0;margin-left:4px}
#date{opacity:.9;margin-top:4px}#greet{font-size:22px;font-weight:500;margin-top:14px}
#focus{margin-top:22px;text-align:center;width:100%;max-width:520px}#focus label{display:block;font-size:13px;opacity:.85;text-transform:uppercase;letter-spacing:1px}
#focus input{width:100%;background:none;border:none;border-bottom:1px solid rgba(255,255,255,.4);color:#fff;font-size:22px;text-align:center;padding:6px 0;outline:none;font-family:inherit}
#focus .done{text-decoration:line-through;opacity:.6}#focus .row{display:flex;align-items:center;gap:8px;justify-content:center;margin-top:6px}#focus .row input[type=checkbox]{width:22px;height:22px;border-bottom:none}
#search{margin-top:26px;width:100%;max-width:520px;display:flex;background:rgba(255,255,255,.92);border-radius:28px;padding:4px 6px 4px 18px;align-items:center;box-shadow:0 8px 32px rgba(0,0,0,.25)}
#search input{flex:1;border:none;background:none;font-size:17px;padding:10px 0;outline:none;color:#111;font-family:inherit}#search button{border:none;background:#0a84ff;color:#fff;border-radius:22px;width:44px;height:44px;font-size:18px}
#links{margin-top:28px;display:grid;grid-template-columns:repeat(4,72px);gap:14px 18px;justify-content:center}
#links a{color:#fff;text-decoration:none;text-align:center;font-size:12px;display:block;position:relative}#links .ic{width:52px;height:52px;border-radius:16px;margin:0 auto 6px;background:rgba(255,255,255,.18);backdrop-filter:blur(10px);display:flex;align-items:center;justify-content:center;font-size:22px;font-weight:600;box-shadow:0 4px 16px rgba(0,0,0,.2)}
#links a span{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}#links .add .ic{background:rgba(255,255,255,.08);border:1px dashed rgba(255,255,255,.4)}
#bottom{position:fixed;left:0;right:0;bottom:0;padding:0 16px calc(env(safe-area-inset-bottom) + 14px);display:flex;justify-content:space-between;align-items:flex-end;font-size:13px;text-shadow:0 1px 6px rgba(0,0,0,.6)}
#quote{max-width:70%;opacity:.95}#quote small{opacity:.7;display:block}#credit{text-align:right;max-width:45%;opacity:.85;font-size:12px}#credit a{color:#fff;text-decoration:none}
#todo_btn{position:fixed;right:16px;bottom:calc(env(safe-area-inset-bottom) + 64px);width:52px;height:52px;border-radius:26px;background:rgba(255,255,255,.18);backdrop-filter:blur(10px);border:none;color:#fff;font-size:22px;box-shadow:0 4px 16px rgba(0,0,0,.25)}
#todo_btn b{position:absolute;top:-4px;right:-4px;background:#ff3b30;font-size:11px;border-radius:9px;min-width:18px;height:18px;line-height:18px;padding:0 4px}
#todo{position:fixed;left:0;right:0;bottom:0;max-height:75vh;background:#fff;color:#111;border-radius:20px 20px 0 0;transform:translateY(110%);transition:transform .3s;display:flex;flex-direction:column;box-shadow:0 -8px 40px rgba(0,0,0,.4);z-index:5}
#todo.on{transform:none}#todo header{display:flex;align-items:center;justify-content:space-between;padding:14px 18px 8px;font-weight:600;font-size:18px}#todo header button{border:none;background:none;color:#0a84ff;font-size:15px}
#todo .list{overflow:auto;padding:0 8px 8px;flex:1}#todo .it{display:flex;align-items:center;gap:12px;padding:10px 10px;border-radius:10px}#todo .it:active{background:#f2f2f7}#todo .it input[type=checkbox]{width:22px;height:22px;flex:none}
#todo .it .tx{flex:1;word-break:break-word}#todo .it.done .tx{text-decoration:line-through;color:#8e8e93}#todo .it .due{font-size:12px;color:#ff9500}#todo .it .due.over{color:#ff3b30}#todo .it .x{border:none;background:none;color:#c7c7cc;font-size:20px}
#todo .add{display:flex;gap:8px;padding:10px 16px calc(env(safe-area-inset-bottom) + 12px);border-top:1px solid #e5e5ea}#todo .add input{flex:1;border:1px solid #e5e5ea;border-radius:12px;padding:10px 14px;font-size:16px;outline:none;font-family:inherit}#todo .add input[type=date]{flex:none;width:44px;padding:0;border:none;background:none;color:transparent;position:relative;overflow:hidden}
#todo .add button{border:none;background:#0a84ff;color:#fff;border-radius:12px;padding:0 16px;font-size:15px}
#todo .empty{text-align:center;color:#8e8e93;padding:32px 0}
#scrim{position:fixed;inset:0;background:rgba(0,0,0,.3);display:none;z-index:4}#scrim.on{display:block}
.hide{display:none!important}
@media(prefers-color-scheme:dark){#todo{background:#1c1c1e;color:#f5f5f7}#todo .it:active{background:#2c2c2e}#todo .add{border-color:#2c2c2e}#todo .add input{background:#2c2c2e;border-color:#2c2c2e;color:#fff}}
</style></head><body>
<div id="bg"></div><div id="dim"></div>
<div id="top"><div id="weather" class="hide"></div><a id="gear" href="lemurx://newtab/settings">⚙</a></div>
<main>
 <div id="clock"></div><div id="date"></div><div id="greet"></div>
 <div id="focus" class="hide"><label>今日专注</label><input id="focus_in" placeholder="今天最重要的一件事是？" autocomplete="off"><div class="row hide" id="focus_row"><input type="checkbox" id="focus_ck"><span id="focus_tx"></span></div></div>
 <form id="search" action="#" onsubmit="return go(event)"><input id="q" name="q" placeholder="搜索或输入网址" autocomplete="off" autocapitalize="off"><button type="submit">→</button></form>
 <div id="links"></div>
</main>
<button id="todo_btn" class="hide">☑<b id="todo_n" class="hide"></b></button>
<div id="scrim"></div>
<div id="todo"><header><span>待办</span><span><button id="clr">清除已完成</button><button id="tclose">完成</button></span></header><div class="list" id="tlist"></div>
 <form class="add" onsubmit="return addTodo(event)"><input id="tin" placeholder="添加待办…" autocomplete="off"><input type="date" id="tdue" title="截止日期"><button type="submit">添加</button></form></div>
<div id="bottom"><div id="quote"></div><div id="credit"></div></div>
<script>
var SEARCH=]] .. json.encode(search_url) .. [[;var ST={};
function api(n,a){return fetch('lemurx://newtab/api/'+n+'?a='+encodeURIComponent(JSON.stringify(a||{}))).then(function(r){return r.json()}).then(function(r){if(r&&r.error)throw new Error(r.error);return r})}
function q(s){return document.querySelector(s)}function esc(s){return String(s||'').replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]})}
function go(e){e.preventDefault();var v=q('#q').value.trim();if(!v)return false;
 if(/^[a-z]+:\/\//i.test(v)||(/^[\w-]+(\.[\w-]+)+(\/.*)?$/.test(v)&&!/\s/.test(v)))location.href=/^[a-z]+:\/\//i.test(v)?v:'https://'+v;else location.href=SEARCH+encodeURIComponent(v);return false}
var WD=['日','一','二','三','四','五','六'];
function tick(){var s=ST.settings||{};if(!s.show_clock){q('#clock').classList.add('hide');q('#date').classList.add('hide');return}
 var d=new Date(),h=d.getHours(),m=d.getMinutes(),sec=d.getSeconds(),ap='';if(!s.clock_24){ap=h<12?'上午':'下午';h=h%12||12}
 q('#clock').innerHTML=(s.clock_24?String(h).padStart(2,'0'):h)+':'+String(m).padStart(2,'0')+(s.show_seconds?':'+String(sec).padStart(2,'0'):'')+(ap?'<small>'+ap+'</small>':'');
 q('#date').textContent=(d.getMonth()+1)+'月'+d.getDate()+'日 星期'+WD[d.getDay()]}
setInterval(tick,1000);
function letter(u){try{var h=new URL(u).host.replace(/^www\./,'');return h[0].toUpperCase()}catch(e){return '·'}}
var HUES=[210,340,160,30,270,190,0,100];function hue(s){var n=0;for(var i=0;i<s.length;i++)n=(n*31+s.charCodeAt(i))>>>0;return HUES[n%HUES.length]}
function render(){var s=ST.settings;q('#greet').textContent=ST.greeting;
 q('#focus').classList.toggle('hide',!s.show_focus);
 if(ST.focus.text){q('#focus_in').classList.add('hide');q('#focus_row').classList.remove('hide');q('#focus_tx').textContent=ST.focus.text;q('#focus_tx').className=ST.focus.done?'done':'';q('#focus_ck').checked=ST.focus.done}else{q('#focus_in').classList.remove('hide');q('#focus_row').classList.add('hide')}
 q('#links').classList.toggle('hide',!s.show_links);var h='';ST.links.forEach(function(l){h+='<a href="'+esc(l.url)+'" data-id="'+esc(l.id)+'" data-auto="'+(l.auto?1:0)+'" data-title="'+esc(l.title)+'"><div class="ic" style="background:hsla('+hue(l.url)+',60%,45%,.75)">'+esc(letter(l.url))+'</div><span>'+esc(l.title)+'</span></a>'});
 h+='<a href="#" class="add" id="link_add"><div class="ic">＋</div><span>添加</span></a>';q('#links').innerHTML=h;
 q('#todo_btn').classList.toggle('hide',!s.show_todo);var open=ST.todos.filter(function(t){return !t.done}).length;q('#todo_n').textContent=open;q('#todo_n').classList.toggle('hide',!open);renderTodos();
 if(ST.quote){q('#quote').innerHTML='“'+esc(ST.quote.text)+'”'+(ST.quote.by?'<small>— '+esc(ST.quote.by)+'</small>':'')}else q('#quote').innerHTML='';
 var b=ST.bg||{};document.documentElement.style.setProperty('--dim','rgba(0,0,0,'+((b.dim||0)/100)+')');
 if(b.engine=='color'){document.body.style.background=b.color||'#1c2833';q('#bg').className='';q('#credit').innerHTML=''}
 else{if(b.title||b.copyright)q('#credit').innerHTML=(b.link?'<a href="'+esc(b.link)+'">':'')+esc(b.title||'')+(b.title&&b.copyright?'<br>':'')+'<small>'+esc((b.copyright||'').replace(/\(©.*?\)/,''))+'</small>'+(b.link?'</a>':'')+' <a href="#" id="bg_next" title="换一张">↻</a>'}
 if(s.show_weather)renderWeather(ST.weather);else q('#weather').classList.add('hide');
 tick()}
function renderWeather(w){var el=q('#weather');if(!w){el.classList.add('hide');return}el.classList.remove('hide');
 el.innerHTML='<span style="font-size:28px">'+w.icon+'</span><div><span class="t">'+Math.round(w.temp)+'°</span> '+esc(w.text)+'<small>'+esc(w.city)+(w.hi!=null?' · '+Math.round(w.lo)+'° / '+Math.round(w.hi)+'°':'')+'</small></div>'}
function fmtDue(d){if(!d)return '';var t=new Date(d+'T00:00:00'),n=new Date();n.setHours(0,0,0,0);var diff=Math.round((t-n)/864e5);return diff==0?'今天':diff==1?'明天':diff<0?(-diff)+'天前':(t.getMonth()+1)+'/'+t.getDate()}
function renderTodos(){var h='';var items=ST.todos.slice().sort(function(a,b){return (a.done-b.done)||((a.due||'9')<(b.due||'9')?-1:1)});
 if(!items.length)h='<div class="empty">没有待办。加一个吧。</div>';
 items.forEach(function(t){var over=t.due&&!t.done&&new Date(t.due+'T23:59:59')<new Date();h+='<div class="it'+(t.done?' done':'')+'" data-id="'+t.id+'"><input type="checkbox"'+(t.done?' checked':'')+'><div class="tx">'+esc(t.text)+(t.due?' <span class="due'+(over?' over':'')+'">'+fmtDue(t.due)+'</span>':'')+'</div><button class="x">×</button></div>'});
 q('#tlist').innerHTML=h}
function addTodo(e){e.preventDefault();var v=q('#tin').value.trim();if(!v)return false;api('todo.add',{text:v,due:q('#tdue').value||null}).then(function(){q('#tin').value='';q('#tdue').value='';load()});return false}
function load(){return api('state').then(function(s){ST=s;render()})}
function loadBg(){var b=ST.bg||{};if(b.engine=='color')return;api('bg').then(function(r){var el=q('#bg');var src=r.data_uri||r.url;if(src){el.style.backgroundImage='url("'+src+'")';el.className='on'}if(r.title!==undefined){ST.bg=Object.assign(ST.bg||{},r);render()}})}
function loadWeather(){if(!(ST.settings&&ST.settings.show_weather))return;api('weather').then(function(r){ST.weather=r.weather;renderWeather(r.weather)})}
document.body.addEventListener('click',function(e){var t=e.target;
 if(t.closest('#bg_next')){e.preventDefault();q('#credit').textContent='换一张…';api('bg.next').then(function(){loadBg()});return}
 if(t.closest('#link_add')){e.preventDefault();var u=prompt('网址');if(!u)return;var n=prompt('名称（留空用域名）')||'';api('link.add',{url:u,title:n}).then(load);return}
 var l=t.closest('#links a[data-id]');if(l&&e.altKey){e.preventDefault()}
 if(t.closest('#todo_btn')){q('#todo').classList.add('on');q('#scrim').classList.add('on');return}
 if(t.closest('#tclose')||t.id=='scrim'){q('#todo').classList.remove('on');q('#scrim').classList.remove('on');return}
 if(t.closest('#clr')){api('todo.clear_done').then(load);return}
 var it=t.closest('#todo .it');if(it){var id=it.dataset.id;if(t.type=='checkbox')api('todo.update',{id:id,done:t.checked}).then(load);else if(t.classList.contains('x'))api('todo.remove',{id:id}).then(load);else if(t.classList.contains('tx')||t.closest('.tx')){var cur=ST.todos.filter(function(x){return x.id==id})[0];var nv=prompt('修改',cur.text);if(nv!==null&&nv.trim())api('todo.update',{id:id,text:nv}).then(load)}return}
 if(t.id=='focus_ck'){api('focus.done',{done:t.checked}).then(load);return}
 if(t.id=='focus_tx'){var nv=prompt('修改今日专注（清空 = 删除）',ST.focus.text);if(nv!==null)api('focus.set',{text:nv}).then(load);return}
});
var pressTimer;q('#links').addEventListener('touchstart',function(e){var l=e.target.closest('a[data-id]');if(!l)return;pressTimer=setTimeout(function(){pressTimer=null;
 var isAuto=l.dataset.auto=='1';var c=confirm(isAuto?'固定 "'+l.dataset.title+'" 到快捷方式？\n取消 = 不再推荐':'删除快捷方式 "'+l.dataset.title+'"？');
 if(isAuto){if(c)api('link.pin',{url:l.href,title:l.dataset.title}).then(load);else api('link.remove',{id:l.dataset.id}).then(load)}else if(c)api('link.remove',{id:l.dataset.id}).then(load)},600)},{passive:true});
q('#links').addEventListener('touchend',function(){if(pressTimer)clearTimeout(pressTimer)});q('#links').addEventListener('touchmove',function(){if(pressTimer)clearTimeout(pressTimer)});
q('#links').addEventListener('click',function(e){if(pressTimer===null){e.preventDefault();pressTimer=undefined}});
q('#focus_in').addEventListener('keydown',function(e){if(e.key=='Enter'){e.preventDefault();var v=this.value.trim();if(v)api('focus.set',{text:v}).then(load)}});
q('#focus_in').addEventListener('blur',function(){var v=this.value.trim();if(v)api('focus.set',{text:v}).then(load)});
load().then(function(){loadBg();loadWeather()});setInterval(function(){load();loadWeather()},10*60*1000);
</script></body></html>]]
    return html
end

S.routes["/settings"] = function(ctx)
    local body = lx.html.settings(S) .. [[<div class="actions"><button data-api="bg.next">换一张壁纸</button><button class="sec" data-api="weather" data-args='{"force":true}'>刷新天气</button><button class="sec" data-api="settings.reset">恢复默认设置</button><a class="btn sec" href="lemurx://scripts/source?f=official/newtab.lua">查看源码</a></div>]]
    return lx.html.page({ title = "新标签页设置", icon = "🌄", body = body, back_url = URL, back_label = "新标签页" })
end

settings:on_change(function(key, value)
    if key == "enabled" or key == "override" then apply_override() end
    if key == "bg" or key == "bg_custom" then lx.after(0, function() refresh_bg(true) end) end
    if key == "weather_city" or key == "show_weather" then
        settings:set("weather_lat", nil, true) settings:set("weather_lon", nil, true)
        lx.after(0, function() refresh_weather(true) end)
    end
end)

-- ===== 启动 =====
do
    local t = data:read_json("todos.json")
    if type(t) == "table" and type(t.items) == "table" then todos = t end
    local l = data:read_json("links.json")
    if type(l) == "table" and type(l.items) == "table" then links = l end
    bg = data:read_json("bg.json")
    weather = data:read_json("weather.json")
    apply_override()
    -- 预热：壁纸和天气提前拿好，开新标签页时不等
    lx.after(3000, function() refresh_bg(false) refresh_weather(false) end)
    lx.every(3600 * 1000, function() refresh_bg(false) end)
end

return S
