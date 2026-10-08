-- privacy/trackers · 内置追踪器名单（Privacy Badger / DuckDuckGo Tracker Radar 的精选子集）
--
-- BLOCK：纯追踪/广告域，第三方出现时直接拦（整个域含子域）；cookieblock=true 的条目改为
--        "放行但不带 Cookie"（站点功能依赖它、又确实在追踪，如 GTM）
-- YELLOW（Privacy Badger 的 yellowlist）：站点功能会依赖的第三方（CDN、登录、验证码、播放器、
--        支付）。它们默认完全不动；只有被启发式学习判定为追踪器时，才降级成 cookieblock 而不是拦截。
-- 学习到的追踪器由 privacy.lua 在运行时叠加在 BLOCK 之上。
--
-- 每条 { host, company, category }；host 按后缀匹配（含子域）。

local M = {}

M.BLOCK = {
    -- Google 广告 / 统计
    { "doubleclick.net", "Google", "ads" }, { "googlesyndication.com", "Google", "ads" }, { "googleadservices.com", "Google", "ads" },
    { "google-analytics.com", "Google", "analytics" }, { "analytics.google.com", "Google", "analytics" }, { "adservice.google.com", "Google", "ads" },
    { "pagead2.googlesyndication.com", "Google", "ads" }, { "googletagservices.com", "Google", "ads" }, { "app-measurement.com", "Google", "analytics" },
    { "firebaseinstallations.googleapis.com", "Google", "analytics" }, { "crashlyticsreports-pa.googleapis.com", "Google", "analytics" },
    -- Meta
    { "pixel.facebook.com", "Meta", "ads" }, { "an.facebook.com", "Meta", "ads" }, { "connect.facebook.net", "Meta", "ads" },
    -- 广告交易 / SSP / DSP
    { "adnxs.com", "Xandr", "ads" }, { "rubiconproject.com", "Magnite", "ads" }, { "pubmatic.com", "PubMatic", "ads" }, { "openx.net", "OpenX", "ads" },
    { "criteo.com", "Criteo", "ads" }, { "criteo.net", "Criteo", "ads" }, { "taboola.com", "Taboola", "ads" }, { "outbrain.com", "Outbrain", "ads" },
    { "adsrvr.org", "The Trade Desk", "ads" }, { "bluekai.com", "Oracle", "ads" }, { "krxd.net", "Salesforce", "ads" }, { "exelator.com", "Nielsen", "ads" },
    { "mathtag.com", "MediaMath", "ads" }, { "casalemedia.com", "Index Exchange", "ads" }, { "indexww.com", "Index Exchange", "ads" }, { "smartadserver.com", "Equativ", "ads" },
    { "advertising.com", "Yahoo", "ads" }, { "yieldmo.com", "Yieldmo", "ads" }, { "media.net", "Media.net", "ads" }, { "moatads.com", "Oracle", "ads" },
    { "doubleverify.com", "DoubleVerify", "ads" }, { "adsafeprotected.com", "IAS", "ads" }, { "bidswitch.net", "BidSwitch", "ads" }, { "sharethrough.com", "Sharethrough", "ads" },
    { "3lift.com", "TripleLift", "ads" }, { "amazon-adsystem.com", "Amazon", "ads" }, { "ads.linkedin.com", "LinkedIn", "ads" }, { "px.ads.linkedin.com", "LinkedIn", "ads" },
    { "ads-twitter.com", "X", "ads" }, { "analytics.twitter.com", "X", "analytics" }, { "t.co", "X", "analytics", only_pixel = true },
    { "bat.bing.com", "Microsoft", "ads" }, { "clarity.ms", "Microsoft", "analytics" }, { "ads.yahoo.com", "Yahoo", "ads" }, { "analytics.yahoo.com", "Yahoo", "analytics" },
    { "analytics.tiktok.com", "TikTok", "ads" }, { "ads.tiktok.com", "TikTok", "ads" }, { "sc-static.net", "Snap", "ads" }, { "tr.snapchat.com", "Snap", "ads" },
    { "ct.pinterest.com", "Pinterest", "ads" }, { "adroll.com", "AdRoll", "ads" }, { "quantserve.com", "Quantcast", "ads" }, { "scorecardresearch.com", "comScore", "analytics" },
    { "demdex.net", "Adobe", "ads" }, { "omtrdc.net", "Adobe", "analytics" }, { "everesttech.net", "Adobe", "ads" }, { "2o7.net", "Adobe", "analytics" },
    { "tapad.com", "Tapad", "ads" }, { "rlcdn.com", "LiveRamp", "ads" }, { "liadm.com", "LiveIntent", "ads" }, { "agkn.com", "Neustar", "ads" },
    { "zemanta.com", "Zemanta", "ads" }, { "mgid.com", "MGID", "ads" }, { "revcontent.com", "Revcontent", "ads" }, { "popads.net", "PopAds", "ads" },
    { "propellerads.com", "PropellerAds", "ads" }, { "exoclick.com", "ExoClick", "ads" }, { "trafficjunky.net", "TrafficJunky", "ads" }, { "juicyads.com", "JuicyAds", "ads" },
    -- 行为分析 / 会话回放
    { "hotjar.com", "Hotjar", "analytics" }, { "hotjar.io", "Hotjar", "analytics" }, { "mouseflow.com", "Mouseflow", "analytics" }, { "fullstory.com", "FullStory", "analytics" },
    { "crazyegg.com", "Crazy Egg", "analytics" }, { "mixpanel.com", "Mixpanel", "analytics" }, { "segment.io", "Twilio", "analytics" }, { "segment.com", "Twilio", "analytics" },
    { "amplitude.com", "Amplitude", "analytics" }, { "chartbeat.com", "Chartbeat", "analytics" }, { "chartbeat.net", "Chartbeat", "analytics" }, { "nr-data.net", "New Relic", "analytics" },
    { "heapanalytics.com", "Heap", "analytics" }, { "kissmetrics.com", "Kissmetrics", "analytics" }, { "luckyorange.com", "Lucky Orange", "analytics" }, { "inspectlet.com", "Inspectlet", "analytics" },
    { "smartlook.com", "Smartlook", "analytics" }, { "logrocket.io", "LogRocket", "analytics" }, { "lr-ingest.io", "LogRocket", "analytics" }, { "sentry-cdn.com", "Sentry", "analytics", cookieblock = true }, { "googletagmanager.com", "Google", "analytics", cookieblock = true }, { "cloudflareinsights.com", "Cloudflare", "analytics", cookieblock = true }, { "sentry.io", "Sentry", "analytics", cookieblock = true },
    { "matomo.cloud", "Matomo", "analytics" }, { "plausible.io", "Plausible", "analytics" }, { "statcounter.com", "StatCounter", "analytics" }, { "histats.com", "Histats", "analytics" },
    { "mc.yandex.ru", "Yandex", "analytics" }, { "an.yandex.ru", "Yandex", "ads" },
    { "branch.io", "Branch", "analytics" }, { "app.link", "Branch", "analytics" }, { "adjust.com", "Adjust", "analytics" }, { "appsflyer.com", "AppsFlyer", "analytics" },
    { "kochava.com", "Kochava", "analytics" }, { "singular.net", "Singular", "analytics" }, { "onesignal.com", "OneSignal", "analytics" }, { "pushwoosh.com", "Pushwoosh", "analytics" },
    { "addthis.com", "Oracle", "social" }, { "sharethis.com", "ShareThis", "social" }, { "addtoany.com", "AddToAny", "social" },
    -- 国内
    { "hm.baidu.com", "百度", "analytics" }, { "pos.baidu.com", "百度", "ads" }, { "cpro.baidu.com", "百度", "ads" }, { "eclick.baidu.com", "百度", "ads" },
    { "union.baidu.com", "百度", "ads" }, { "nsclick.baidu.com", "百度", "analytics" }, { "cbjs.baidu.com", "百度", "ads" }, { "hmma.baidu.com", "百度", "analytics" },
    { "cnzz.com", "友盟", "analytics" }, { "umeng.com", "友盟", "analytics" }, { "umengcloud.com", "友盟", "analytics" }, { "51.la", "51.LA", "analytics" },
    { "talkingdata.com", "TalkingData", "analytics" }, { "talkingdata.net", "TalkingData", "analytics" }, { "growingio.com", "GrowingIO", "analytics" }, { "sensorsdata.cn", "神策", "analytics" },
    { "zhugeio.com", "诸葛io", "analytics" }, { "tanx.com", "阿里妈妈", "ads" }, { "mmstat.com", "阿里", "analytics" }, { "alimama.com", "阿里妈妈", "ads" },
    { "simba.taobao.com", "阿里妈妈", "ads" }, { "gdt.qq.com", "腾讯广告", "ads" }, { "l.qq.com", "腾讯广告", "ads" }, { "e.qq.com", "腾讯广告", "ads" },
    { "pingjs.qq.com", "腾讯", "analytics" }, { "beacon.qq.com", "腾讯", "analytics" }, { "pingtcss.qq.com", "腾讯", "analytics" }, { "report.url.cn", "腾讯", "analytics" },
    { "pingfore.qq.com", "腾讯", "analytics" }, { "otheve.beacon.qq.com", "腾讯", "analytics" }, { "irs01.com", "秒针", "ads" }, { "irs01.net", "秒针", "ads" },
    { "miaozhen.com", "秒针", "ads" }, { "admaster.com.cn", "AdMaster", "ads" }, { "adsage.com", "艾德思奇", "ads" }, { "mediav.com", "聚效", "ads" },
    { "yoyi.com.cn", "悠易", "ads" }, { "ipinyou.com", "品友", "ads" }, { "reachmax.cn", "ReachMax", "ads" }, { "adview.cn", "AdView", "ads" },
    { "tracking.miui.com", "小米", "analytics" }, { "data.mistat.xiaomi.com", "小米", "analytics" }, { "gridsumdissector.com", "国双", "analytics" }, { "gridsum.com", "国双", "analytics" },
    { "wrating.com", "艾瑞", "analytics" }, { "irs.gridsum.com", "国双", "analytics" }, 
    { "cpro.baidustatic.com", "百度", "ads" }, { "dup.baidustatic.com", "百度", "ads" }, 
    { "pb.sogou.com", "搜狗", "analytics" }, { "dw.sogou.com", "搜狗", "analytics" }, { "hm.baidu.com", "百度", "analytics" }, { "zz.bdstatic.com", "百度", "analytics" },
    { "log.mmstat.com", "阿里", "analytics" }, { "gm.mmstat.com", "阿里", "analytics" }, { "wgo.mmstat.com", "阿里", "analytics" }, { "ac.mmstat.com", "阿里", "analytics" },
    { "getui.com", "个推", "analytics" }, { "igexin.com", "个推", "analytics" }, { "jpush.cn", "极光", "analytics" }, { "jiguang.cn", "极光", "analytics" },
    { "mob.com", "MobTech", "analytics" }, { "dgtle.com", "DGTLE", "ads" }, { "mercury.jd.com", "京东", "analytics" }, { "wl.jd.com", "京东", "analytics" },
    { "tencentmind.com", "腾讯", "ads" }, { "ad.360.cn", "360", "ads" }, { "s.360.cn", "360", "analytics" }, { "mediav.com", "360", "ads" },
    { "log.yizhibo.com", "一直播", "analytics" }, 
    { "ad.toutiao.com", "字节", "ads" }, { "pangolin-sdk-toutiao.com", "穿山甲", "ads" }, { "pglstatp-toutiao.com", "穿山甲", "ads" }, { "byteoversea.com", "字节", "analytics" },
    { "mon.zijieapi.com", "字节", "analytics" }, { "log.snssdk.com", "字节", "analytics" }, { "toblog.ctobsnssdk.com", "字节", "analytics" }, { "applog.uc.cn", "UC", "analytics" },
    { "data-track.tuhu.cn", "途虎", "analytics" }, { "tongji.baidu.com", "百度", "analytics" },
}

M.YELLOW = {
    -- CDN
    { "cloudflare.com", "Cloudflare", "cdn" }, { "akamaihd.net", "Akamai", "cdn" }, { "akamaized.net", "Akamai", "cdn" },
    { "cloudfront.net", "Amazon", "cdn" }, { "amazonaws.com", "Amazon", "cdn" }, { "googleapis.com", "Google", "cdn" }, { "gstatic.com", "Google", "cdn" },
    { "googleusercontent.com", "Google", "cdn" }, { "googlevideo.com", "Google", "cdn" }, { "ggpht.com", "Google", "cdn" }, { "gvt1.com", "Google", "cdn" },
    { "jsdelivr.net", "jsDelivr", "cdn" }, { "unpkg.com", "unpkg", "cdn" }, { "cdnjs.cloudflare.com", "Cloudflare", "cdn" }, { "bootstrapcdn.com", "Bootstrap", "cdn" },
    { "fontawesome.com", "Font Awesome", "cdn" }, { "fonts.googleapis.com", "Google", "cdn" }, { "typekit.net", "Adobe", "cdn" }, { "fastly.net", "Fastly", "cdn" },
    { "azureedge.net", "Microsoft", "cdn" }, { "windows.net", "Microsoft", "cdn" }, { "msecnd.net", "Microsoft", "cdn" }, { "wp.com", "Automattic", "cdn" },
    { "gravatar.com", "Automattic", "cdn" }, { "githubusercontent.com", "GitHub", "cdn" }, { "github.io", "GitHub", "cdn" }, { "imgur.com", "Imgur", "cdn" },
    { "giphy.com", "Giphy", "cdn" }, { "alicdn.com", "阿里", "cdn" }, { "aliyuncs.com", "阿里", "cdn" }, { "bdstatic.com", "百度", "cdn" },
    { "bdimg.com", "百度", "cdn" }, { "gtimg.com", "腾讯", "cdn" }, { "gtimg.cn", "腾讯", "cdn" }, { "qpic.cn", "腾讯", "cdn" }, { "qlogo.cn", "腾讯", "cdn" },
    { "myqcloud.com", "腾讯", "cdn" }, { "hdslb.com", "哔哩哔哩", "cdn" }, { "bilivideo.com", "哔哩哔哩", "cdn" }, { "bilivideo.cn", "哔哩哔哩", "cdn" },
    { "sinaimg.cn", "微博", "cdn" }, { "360buyimg.com", "京东", "cdn" }, { "zhimg.com", "知乎", "cdn" },
    { "xhscdn.com", "小红书", "cdn" }, { "byteimg.com", "字节", "cdn" }, { "douyinpic.com", "字节", "cdn" }, { "pstatp.com", "字节", "cdn" },
    { "126.net", "网易", "cdn" }, { "127.net", "网易", "cdn" }, { "sogoucdn.com", "搜狗", "cdn" }, { "csdnimg.cn", "CSDN", "cdn" },
    { "bootcss.com", "BootCDN", "cdn" }, { "staticfile.org", "七牛", "cdn" }, { "baomitu.com", "360", "cdn" }, { "loli.net", "loli", "cdn" },
    { "fbcdn.net", "Meta", "cdn" }, { "cdninstagram.com", "Meta", "cdn" }, { "twimg.com", "X", "cdn" }, { "ytimg.com", "Google", "cdn" },
    { "vimeocdn.com", "Vimeo", "cdn" }, { "redditstatic.com", "Reddit", "cdn" }, { "redditmedia.com", "Reddit", "cdn" }, { "licdn.com", "LinkedIn", "cdn" },
    { "pinimg.com", "Pinterest", "cdn" }, { "tiktokcdn.com", "TikTok", "cdn" }, { "ttwstatic.com", "TikTok", "cdn" }, { "spotifycdn.com", "Spotify", "cdn" },
    { "scdn.co", "Spotify", "cdn" }, { "steamstatic.com", "Valve", "cdn" }, { "discordapp.com", "Discord", "cdn" }, { "discord.com", "Discord", "cdn" },
    -- 登录 / 验证码 / 支付 / 嵌入
    { "recaptcha.net", "Google", "captcha" }, { "google.com", "Google", "embed" }, { "accounts.google.com", "Google", "login" }, { "hcaptcha.com", "hCaptcha", "captcha" },
    { "challenges.cloudflare.com", "Cloudflare", "captcha" }, { "geetest.com", "极验", "captcha" }, { "vaptcha.com", "Vaptcha", "captcha" }, { "captcha.qq.com", "腾讯", "captcha" },
    { "paypal.com", "PayPal", "payment" }, { "paypalobjects.com", "PayPal", "payment" }, { "stripe.com", "Stripe", "payment" }, { "stripe.network", "Stripe", "payment" },
    { "alipay.com", "支付宝", "payment" }, { "alipayobjects.com", "支付宝", "payment" }, { "wechatpay.cn", "微信支付", "payment" }, { "apple.com", "Apple", "login" },
    { "microsoft.com", "Microsoft", "login" }, { "live.com", "Microsoft", "login" }, { "microsoftonline.com", "Microsoft", "login" }, { "youtube.com", "Google", "embed" },
    { "youtube-nocookie.com", "Google", "embed" }, { "vimeo.com", "Vimeo", "embed" }, { "player.bilibili.com", "哔哩哔哩", "embed" }, { "twitter.com", "X", "embed" },
    { "x.com", "X", "embed" }, { "platform.twitter.com", "X", "embed" }, { "facebook.com", "Meta", "embed" }, { "instagram.com", "Meta", "embed" },
    { "disqus.com", "Disqus", "embed" }, { "disquscdn.com", "Disqus", "embed" }, { "gitalk.io", "Gitalk", "embed" }, { "utteranc.es", "utterances", "embed" },
    { "giscus.app", "giscus", "embed" }, { "codepen.io", "CodePen", "embed" }, { "jsfiddle.net", "JSFiddle", "embed" }, { "soundcloud.com", "SoundCloud", "embed" },
    { "spotify.com", "Spotify", "embed" }, { "google.com.hk", "Google", "embed" }, 
    { "graph.qq.com", "腾讯", "login" },
    { "connect.qq.com", "腾讯", "login" }, { "ptlogin2.qq.com", "腾讯", "login" }, { "open.weixin.qq.com", "微信", "login" }, { "api.weibo.com", "微博", "login" },
    { "passport.weibo.com", "微博", "login" }, { "amap.com", "高德", "embed" }, { "map.baidu.com", "百度", "embed" }, { "api.map.baidu.com", "百度", "embed" },
    { "maps.googleapis.com", "Google", "embed" }, { "browser-intake-datadoghq.com", "Datadog", "analytics" }, { "newrelic.com", "New Relic", "analytics" },
    { "intercom.io", "Intercom", "chat" }, { "intercomcdn.com", "Intercom", "chat" }, { "crisp.chat", "Crisp", "chat" }, { "zdassets.com", "Zendesk", "chat" },
    { "zendesk.com", "Zendesk", "chat" }, { "tawk.to", "tawk.to", "chat" }, { "livechatinc.com", "LiveChat", "chat" }, { "hubspot.com", "HubSpot", "chat" },
    { "hs-scripts.com", "HubSpot", "analytics" }, { "hsforms.net", "HubSpot", "embed" }, { "typeform.com", "Typeform", "embed" }, { "algolia.net", "Algolia", "embed" },
    { "algolianet.com", "Algolia", "embed" }, { "mathjax.org", "MathJax", "cdn" }, { "polyfill.io", "polyfill", "cdn" }, { "gravatar.com", "Automattic", "cdn" },
}

-- 索引：host -> entry（精确 host 与后缀都查）
local block_idx, yellow_idx = {}, {}
for _, e in ipairs(M.BLOCK) do block_idx[e[1]] = e end
for _, e in ipairs(M.YELLOW) do yellow_idx[e[1]] = e end

-- 沿后缀链查：a.b.c.com → a.b.c.com, b.c.com, c.com
local function lookup(idx, host)
    local h = host
    while h and h ~= "" do
        local e = idx[h]
        if e then return e, h end
        local dot = h:find(".", 1, true)
        if not dot then break end
        h = h:sub(dot + 1)
    end
    return nil
end

-- classify(host) -> "block"|"cookieblock"|nil, entry     （只查内置 BLOCK 表）
function M.classify(host)
    if type(host) ~= "string" then return nil end
    local b = lookup(block_idx, host:lower())
    if not b then return nil end
    if b.cookieblock then return "cookieblock", b end
    return "block", b
end

-- yellow(host) -> entry|nil   站点功能依赖的第三方：学习判定为追踪也只 cookieblock
function M.yellow(host)
    if type(host) ~= "string" then return nil end
    return (lookup(yellow_idx, host:lower()))
end

function M.company_of(host)
    local _, e = M.classify(host)
    if e then return e[2] end
    e = M.yellow(host)
    return e and e[2] or nil
end

return M
