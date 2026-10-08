-- privacy/clearurls · 链接去追踪（ClearURLs 的 Lua 实现）
--
-- 纯 Lua，浏览器进程和渲染进程共用：
--   clean(url [, custom]) -> new_url | nil      去掉追踪参数（nil 表示没改动）
--   unwrap(url)           -> target | nil       拆掉跳转追踪（google /url?q=、l.facebook.com …）
--   process(url [, custom]) -> new_url | nil, kind   先 unwrap 再 clean；kind = "unwrap"|"clean"
--
-- 规则是从 ClearURLs 的 rules.json 里按中文用户常用站点手工精选并补充的：
--   * GLOBAL：所有站点都去掉的参数（utm_*、fbclid、gclid …）
--   * SITES：按站点（含子域）追加的参数 / 前缀 / 路径整形
--   * REDIRECTS：跳转追踪站点 → 取目标参数
-- 原则：宁少勿多——凡是可能影响功能的参数（分页、搜索词、商品规格）一律不碰。

local util = require("lx.util")
local url = util.url

local M = {}

-- ===== 全局参数 =====
local GLOBAL_EXACT = {}
for _, k in ipairs({
    -- 广告平台点击 ID
    "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid", "ttclid",
    "igshid", "igsh", "li_fat_id", "srsltid", "rb_clickid", "s_kwcid", "ef_id", "wickedid", "zanpid",
    "vero_id", "vero_conv", "oly_anon_id", "oly_enc_id", "mkt_tok", "_hsenc", "_hsmi", "hsCtaTracking",
    "mc_cid", "mc_eid", "_openstat", "yadclid", "ymclid", "_ga", "_gl", "_branch_match_id", "_branch_referrer",
    "guccounter", "guce_referrer", "guce_referrer_sig", "cmpid", "ncid", "ito", "sc_campaign", "sc_channel",
    "sc_content", "sc_medium", "sc_outcome", "sc_geo", "sc_country", "ref_src", "ref_url", "refsrc",
    "spm", "scm", "pvid", "ali_trackid", "ali_refid", "__tn__", "_trkparms", "_trksid", "mibextid",
    "trk", "trkCampaign", "trackingId", "share_source", "share_medium", "share_plat", "share_session_id",
    "share_tag", "share_from", "unique_k", "vd_source", "from_source", "spm_id_from", "wxfid",
}) do GLOBAL_EXACT[k] = true end
local GLOBAL_PREFIX = { "utm_", "pk_", "piwik_", "mtm_", "matomo_", "hsa_", "adjust_", "_bta_", "__cft__", "__xts__", "at_custom", "at_medium", "at_campaign" }

-- ===== 站点规则 =====
-- host：util.host_matches 语法（"*.example.com" 含所有子域及本身）
local SITES = {
    { host = "*.amazon.com", exact = { "pd_rd_r", "pd_rd_w", "pd_rd_wg", "pd_rd_i", "pf_rd_r", "pf_rd_p", "pf_rd_s", "pf_rd_t", "pf_rd_i", "pf_rd_m", "qid", "sr", "srs", "spIA", "ms3_c", "ie", "refRID", "colid", "coliid", "linkId", "linkCode", "tag", "ascsubtag", "creative", "creativeASIN", "ref_", "ref", "dchild", "crid", "sprefix", "pd_rd_plhdr", "content-id", "dib", "dib_tag" },
      prefix = { "__mk_", "pd_rd_", "pf_rd_" },
      path = function(p) return (p:gsub("/ref=[^/?#]*", "")) end },
    -- 其他区域 amazon 同规则
    { host = "*.amazon.cn", alias = "*.amazon.com" }, { host = "*.amazon.co.jp", alias = "*.amazon.com" },
    { host = "*.amazon.co.uk", alias = "*.amazon.com" }, { host = "*.amazon.de", alias = "*.amazon.com" },
    { host = "*.amzn.to", alias = "*.amazon.com" },
    { host = "*.google.com", exact = { "ved", "ei", "sa", "usg", "sca_esv", "sca_upv", "sxsrf", "oq", "gs_lcp", "gs_lp", "gs_lcrp", "uact", "sclient", "bih", "biw", "dpr", "sourceid", "aqs", "zx", "ictx", "iflsig", "ie", "source", "fbs", "gs_ssp", "cshid", "udm", "sei", "ictx", "ved", "opi", "fbs" },
      only_paths = { "^/search", "^/$", "^/webhp" } },
    { host = "*.youtube.com", exact = { "feature", "si", "pp", "kw", "ab_channel", "embeds_referring_euri", "embeds_referring_origin", "source_ve_path", "themeRefresh" } },
    { host = "youtu.be", exact = { "si", "feature" } },
    { host = "*.bilibili.com", exact = { "spm_id_from", "from_source", "msource", "bsource", "seid", "source", "session_id", "visit_id", "sourceFrom", "from_spmid", "share_source", "share_medium", "share_plat", "share_session_id", "share_tag", "share_from", "unique_k", "timestamp", "vd_source", "bbid", "ts", "up_id", "is_story_h5", "plat_id", "buvid", "spmid", "broadcast_type", "is_room_feed", "share_times", "hotRank", "live_from", "launch_id", "session_id", "from", "-Arouter", "network", "platform", "mobi_app", "refer_from", "is_refresh", "trackid", "goFrom", "csource", "from_module" } },
    { host = "b23.tv", alias = "*.bilibili.com" },
    { host = "*.taobao.com", exact = { "spm", "scm", "pvid", "ali_trackid", "ali_refid", "mi_id", "utparam", "bxsign", "ns", "abbucket", "xxc", "share_crt_v", "un", "ut_sk", "cpp", "shareurl", "short_name", "sp_abtk", "sp_tk", "suid", "sourceType", "bc_fl_src", "app", "tbSocialPopKey", "shareUniqueId", "priceTId", "utkn", "pisk", "ttid", "wxsign", "bxsign", "sp_tk", "cv", "traceId" } },
    { host = "*.tmall.com", alias = "*.taobao.com" }, { host = "*.tmall.hk", alias = "*.taobao.com" }, { host = "*.aliexpress.com", exact = { "spm", "scm", "pvid", "algo_pvid", "algo_exp_id", "pdp_npi", "sourceType", "gatewayAdapt", "terminal_id", "sk", "aff_platform", "aff_trace_key", "afSmartRedirect", "srcSns", "businessType", "templateId", "spreadType", "tt", "bizType", "social_params", "curPageLogUid", "utparam-url", "_randl_shipto", "_randl_currency" }, prefix = { "aff_" } },
    { host = "*.jd.com", exact = { "cu", "ptag", "sdx", "ad_od", "ext", "abt", "rid", "utm_term", "gx", "gxd", "ad_od", "wxa_abtest", "PTAG", "shareSource", "utm_user" } },
    { host = "*.zhihu.com", exact = { "utm_psn", "utm_id", "hybrid_search_source", "hybrid_search_extra", "search_source" } },
    { host = "*.xiaohongshu.com", exact = { "app_platform", "app_version", "share_from_user_hidden", "xsec_source", "author_share", "xhsshare", "shareRedId", "apptime", "share_id", "exSource", "verifyUuid", "verifyType", "verifyBiz" } },
    { host = "*.douyin.com", exact = { "previous_page", "enter_from", "enter_method", "extra_params", "share_source", "iid", "did", "mid", "titleType", "schema_type", "share_sign", "share_version", "timestamp", "with_sec_did", "u_code", "tt_from", "app", "region", "ts", "from_ssr", "utm_source", "from_aid" } },
    { host = "*.tiktok.com", exact = { "_r", "_t", "is_from_webapp", "sender_device", "sender_web_id", "web_id", "share_app_id", "share_link_id", "share_item_id", "source", "checksum", "sec_uid", "share_author_id", "tt_from", "u_code", "user_id", "timestamp", "is_copy_url", "enter_from", "enter_method", "refer", "preview_pb", "language", "_d" } },
    { host = "*.weibo.com", exact = { "weibo_id", "dt_dapp", "wm", "sourceType", "from", "type", "pagetype", "featurecode", "luicode", "lfid" } },
    { host = "*.weibo.cn", alias = "*.weibo.com" },
    { host = "*.twitter.com", exact = { "s", "t", "cxt", "ref_src", "ref_url", "twclid" } },
    { host = "*.x.com", alias = "*.twitter.com" }, { host = "t.co", alias = "*.twitter.com" },
    { host = "*.facebook.com", exact = { "__tn__", "eid", "ref", "hc_ref", "hc_location", "comment_tracking", "notif_id", "notif_t", "refsrc", "_rdc", "_rdr", "mibextid", "fref", "pnref", "rc", "fb_dtsg_ag", "tn-str", "__cft__[0]", "__xts__[0]", "extid", "app", "video_source" } },
    { host = "*.instagram.com", exact = { "igshid", "igsh", "img_index", "hl" } },
    { host = "*.reddit.com", exact = { "share_id", "ref", "ref_source", "rdt", "correlation_id", "ref_campaign", "utm_name", "$deep_link", "post_fullname", "$android_deeplink_path", "$deeplink_path", "$ios_deeplink_path" } },
    { host = "*.linkedin.com", exact = { "trk", "trkInfo", "lipi", "licu", "refId", "trackingId", "midToken", "midSig", "eid", "otpToken", "original_referer", "originalSubdomain", "trkEmail", "lici" } },
    { host = "*.spotify.com", exact = { "si", "context", "nd", "dl_branch", "_branch_match_id", "_branch_referrer", "referral", "pi" } },
    { host = "*.bing.com", exact = { "cvid", "form", "FORM", "pc", "sk", "sc", "sp", "qs", "ghc", "ghsh", "ghacc", "ghpl", "pq", "ntref", "pglt", "qpvt", "refig", "toWww", "redig", "mkt" } },
    { host = "*.ebay.com", exact = { "_trkparms", "_trksid", "hash", "mkevt", "mkcid", "mkrid", "campid", "toolid", "customid", "ssspo", "sssrc", "ssuid", "amdata", "mkgroupid", "_from", "epid", "itmmeta", "itmprp", "widget_ver", "media", "norover", "siteid" } },
    { host = "*.medium.com", exact = { "source", "gi", "postPublishedType" } },
    { host = "*.github.com", exact = { "email_source", "email_token", "notification_referrer_id", "ref_cta", "ref_loc", "ref_page", "source" } },
    { host = "*.stackoverflow.com", exact = { "rq", "lq", "ref", "so_medium", "so_source", "cb" } },
    { host = "*.163.com", exact = { "userid", "app_version", "dlt", "from", "sc", "tn", "market", "uct2" } },
    { host = "*.qq.com", exact = { "ADTAG", "adtag", "sharer_sharetime", "sharer_shareid", "srcid", "mpshare", "scene", "subscene", "clicktime", "enterid", "exportkey", "pass_ticket", "wx_header", "ascene", "devicetype", "version", "lang", "nettype", "abtest_cookie", "sessionid", "uin", "key", "fontgear" },
      only_paths = { "^/s$", "^/s/" } },
    { host = "*.weixin.qq.com", alias = "*.qq.com" },
    { host = "*.douban.com", exact = { "_i", "_dtcc", "dt_dapp", "dt_platform", "from", "source", "channel" } },
    { host = "*.smzdm.com", exact = { "send_by", "invite_code", "share_id", "share_from", "zdm_ss", "sort", "from" } },
    { host = "*.pinduoduo.com", exact = { "refer_share_id", "refer_share_uin", "refer_share_channel", "share_uin", "refer_page_name", "refer_page_id", "refer_page_sn", "_oc_trace_mark", "_x_share_id", "_wv", "_wvx", "refer_share_form", "share_channel", "_x_ddjb_act", "_x_query", "_oak_share_snapshot_num" } },
    { host = "*.yangkeduo.com", alias = "*.pinduoduo.com" },
    { host = "*.kuaishou.com", exact = { "fid", "cc", "shareMethod", "kpn", "subBiz", "shareId", "shareToken", "shareResourceType", "userId", "shareType", "et", "shareMode", "originShareId", "appType", "shareObjectId", "shareUrlOpened", "timestamp", "utm_source", "utm_medium", "utm_campaign", "photoId", "share_device_id", "shareChannel" } },
    { host = "*.csdn.net", exact = { "spm", "utm_medium", "depth_1-utm_source", "depth_1-utm_medium", "dist_request_id", "request_id", "biz_id", "utm_relevant_index", "ops_request_misc", "ydreferer", "SEO_INDEX_ID", "ps_kw" } },
    { host = "*.zhipin.com", exact = { "ka", "sid", "share_from", "utm_source" } },
    { host = "*.apple.com", exact = { "mt", "ign-mpt", "pt", "ct", "at", "itsct", "itscg", "ls", "app", "uo", "itscg", "uo", "referrer" } },
    { host = "*.netflix.com", exact = { "trackId", "tctx", "trkid", "jbv", "jbp", "jbr", "so" } },
    { host = "*.twitch.tv", exact = { "tt_content", "tt_medium", "tt_email_id", "referrer" } },
    { host = "*.yahoo.com", exact = { "guccounter", "guce_referrer", "guce_referrer_sig", "soc_src", "soc_trk", "ncid" } },
    { host = "*.walmart.com", exact = { "athbdg", "athcpid", "athpgid", "athznid", "athieid", "athstid", "athguid", "athancid", "athena", "adsRedirect", "from", "wmlspartner", "selectedSellerId", "affp1", "affillinktype", "veh", "wl13", "athAsset" } },
    { host = "*.pixiv.net", exact = { "ref", "return_to", "p" }, only_paths = { "^/artworks" } },
    { host = "*.steampowered.com", exact = { "snr", "curator_clanid", "utm_content", "l" } },
    { host = "*.steamcommunity.com", exact = { "snr", "l" } },
    { host = "*.wikipedia.org", exact = { "wprov", "useskin", "printable", "oldformat", "utm_source" } },
    { host = "*.microsoft.com", exact = { "WT.mc_id", "wt.mc_id", "ocid", "ranMID", "ranEAID", "ranSiteID", "epi", "irgwc", "OCID", "tduid", "irclickid", "ef_id", "s_kwcid" } },
    { host = "*.humblebundle.com", exact = { "partner", "charity", "hmb_source", "hmb_medium", "hmb_campaign" } },
    { host = "*.imdb.com", exact = { "ref_", "pf_rd_m", "pf_rd_p", "pf_rd_r", "pf_rd_s", "pf_rd_t", "pf_rd_i" }, path = function(p) return (p:gsub("/ref=[^/?#]*", "")) end },
    { host = "*.aliyun.com", exact = { "spm", "scm", "source", "userCode" } },
    { host = "*.alibaba.com", exact = { "spm", "scm", "pvid", "tracelog", "mark", "from", "tracking_id", "ali_trackid" } },
    { host = "*.mi.com", exact = { "cfrom", "from", "utm_source", "channel", "ref" } },
}
do
    local by_host = {}
    for _, s in ipairs(SITES) do by_host[s.host] = s end
    for _, s in ipairs(SITES) do
        if s.alias then
            local src = by_host[s.alias]
            s.exact, s.prefix, s.path, s.only_paths = src.exact, src.prefix, src.path, src.only_paths
        end
        local set = {}
        for _, k in ipairs(s.exact or {}) do set[k] = true end
        s.set = set
    end
end

-- ===== 跳转追踪 =====
-- param：装着目标的参数名（按顺序试）；decode：特殊编码
local REDIRECTS = {
    { host = "*.google.com", path = "^/url", param = { "q", "url" } },
    { host = "*.google.com", path = "^/imgres", param = { "imgurl" } },
    { host = "*.googleadservices.com", path = "^/pagead/aclk", param = { "adurl" } },
    { host = "*.youtube.com", path = "^/redirect", param = { "q" } },
    { host = "l.facebook.com", path = "^/l%.php", param = { "u" } },
    { host = "lm.facebook.com", path = "^/l%.php", param = { "u" } },
    { host = "l.messenger.com", path = "^/l%.php", param = { "u" } },
    { host = "l.instagram.com", path = "^/", param = { "u" } },
    { host = "l.threads.net", path = "^/", param = { "u" } },
    { host = "t.umblr.com", path = "^/redirect", param = { "z" } },
    { host = "out.reddit.com", path = "^/", param = { "url" } },
    { host = "*.linkedin.com", path = "^/redir/redirect", param = { "url" } },
    { host = "*.linkedin.com", path = "^/safety/go", param = { "url" } },
    { host = "away.vk.com", path = "^/away%.php", param = { "to" } },
    { host = "link.zhihu.com", path = "^/", param = { "target" } },
    { host = "*.jianshu.com", path = "^/go%-wild", param = { "url" } },
    { host = "gate.sc", path = "^/", param = { "url" } },
    { host = "exit.sc", path = "^/", param = { "url" } },
    { host = "c.pc.qq.com", path = "^/", param = { "pfurl", "url" } },
    { host = "*.steamcommunity.com", path = "^/linkfilter/", param = { "url", "u" } },
    { host = "*.duckduckgo.com", path = "^/l/", param = { "uddg" } },
    { host = "*.bing.com", path = "^/ck/a", param = { "u" }, decode = "bing" },
    { host = "*.disq.us", path = "^/url", param = { "url" }, strip_suffix = ":%w+$" },
    { host = "slack-redir.net", path = "^/link", param = { "url" } },
    { host = "*.weibo.cn", path = "^/sinaurl", param = { "u", "toasturl" } },
    { host = "*.weibo.com", path = "^/sinaurl", param = { "u", "toasturl" } },
    { host = "link.csdn.net", path = "^/", param = { "target" } },
    { host = "link.juejin.cn", path = "^/", param = { "target" } },
    { host = "link.segmentfault.com", path = "^/", param = { "url" } },
    { host = "*.afdian.net", path = "^/redirect", param = { "url" } },
    { host = "href.li", path = "^/", raw_query = true },
    { host = "*.mail.qq.com", path = "^/cgi%-bin/readtemplate", param = { "gourl" } },
    { host = "leaving.wechat.qq.com", path = "^/", param = { "url" } },
    { host = "*.dcinside.com", path = "^/", param = { "url" } },
    { host = "*.deviantart.com", path = "^/users/outgoing", param = { "url" }, raw_query = true },
    { host = "*.pixiv.net", path = "^/jump%.php", raw_query = true },
    { host = "*.nicovideo.jp", path = "^/", param = { "url" } },
    { host = "*.tumblr.com", path = "^/redirect", param = { "z" } },
    { host = "*.mozilla.org", path = "^/outgoing", param = { "url" } },
    { host = "www.gamer.com.tw", path = "^/ref/", param = { "url" } },
}

local function base64_decode_url(s)
    s = s:gsub("-", "+"):gsub("_", "/")
    local pad = #s % 4
    if pad > 0 then s = s .. string.rep("=", 4 - pad) end
    return util.base64_decode(s)
end

-- 跳转追踪：返回目标 URL（已 decode），没有则 nil
function M.unwrap(u)
    local p = url.parse(u)
    if not (p and p.host) then return nil end
    for _, r in ipairs(REDIRECTS) do
        if util.host_matches(p.host, r.host) and (p.path or "/"):find(r.path) then
            local target
            if r.raw_query then
                target = p.query and url.decode(p.query) or nil
            else
                local q = url.query(p.query)
                for _, name in ipairs(r.param) do
                    if type(q[name]) == "string" and q[name] ~= "" then
                        target = q[name]
                        break
                    end
                end
            end
            if target then
                if r.decode == "bing" then
                    -- bing 的 u=a1<base64url>
                    if target:sub(1, 2) == "a1" then
                        target = base64_decode_url(target:sub(3)) or ""
                    end
                end
                if r.strip_suffix then target = target:gsub(r.strip_suffix, "") end
                target = util.trim(target)
                if target:match("^https?://[%w%.%-]") and target ~= u then return target end
            end
        end
    end
    return nil
end

local function param_tracked(name, site, custom)
    if GLOBAL_EXACT[name] then return true end
    for _, pre in ipairs(GLOBAL_PREFIX) do
        if name:sub(1, #pre) == pre then return true end
    end
    if site then
        if site.set[name] then return true end
        for _, pre in ipairs(site.prefix or {}) do
            if name:sub(1, #pre) == pre then return true end
        end
    end
    if custom then
        for _, c in ipairs(custom) do
            if c ~= "" then
                if c:sub(-1) == "*" then
                    if name:sub(1, #c - 1) == c:sub(1, -2) then return true end
                elseif c == name then
                    return true
                end
            end
        end
    end
    return false
end

local function site_for(host, path)
    for _, s in ipairs(SITES) do
        if util.host_matches(host, s.host) then
            if s.only_paths then
                local ok = false
                for _, pat in ipairs(s.only_paths) do
                    if path:find(pat) then ok = true break end
                end
                if not ok then return nil, s end
            end
            return s
        end
    end
    return nil
end

-- 去掉追踪参数；custom 是用户自定义参数名列表（支持末尾 * 前缀匹配）。
-- 返回 新 URL, 去掉的参数个数；没改动返回 nil
function M.clean(u, custom)
    if type(u) ~= "string" or not u:match("^https?://") then return nil end
    local p = url.parse(u)
    if not (p and p.host) then return nil end
    local path = p.path or "/"
    local site, path_site = site_for(p.host, path)
    local removed = 0
    local changed = false
    -- 路径整形（amazon /ref=…）
    local shaper = (site or path_site or {}).path
    if shaper then
        local np = shaper(path)
        if np ~= path and np ~= "" then
            p.path = np
            changed = true
        end
    end
    if p.query and p.query ~= "" then
        local keep = {}
        for _, kv in ipairs(url.query_pairs(p.query)) do
            local name = url.decode(kv[1])
            if param_tracked(name, site, custom) then
                removed = removed + 1
            else
                keep[#keep + 1] = kv[2] ~= "" and (kv[1] .. "=" .. kv[2]) or kv[1]
            end
        end
        if removed > 0 then
            p.query = #keep > 0 and table.concat(keep, "&") or nil
            changed = true
        end
    end
    -- 片段里的追踪（#utm_source=…、#xtor=）
    if p.fragment and (p.fragment:match("^utm_") or p.fragment:match("^xtor=") or p.fragment:match("^Echobox=") or p.fragment:match("^_=_$")) then
        p.fragment = nil
        removed = removed + 1
        changed = true
    end
    if not changed then return nil end
    return url.build(p), removed
end

-- 先拆跳转，再清参数
function M.process(u, custom)
    local target = M.unwrap(u)
    if target then
        local cleaned = M.clean(target, custom)
        return cleaned or target, "unwrap"
    end
    local cleaned, n = M.clean(u, custom)
    if cleaned then return cleaned, "clean", n end
    return nil
end

M.SITES = SITES
M.REDIRECTS = REDIRECTS
M.GLOBAL_EXACT = GLOBAL_EXACT
M.GLOBAL_PREFIX = GLOBAL_PREFIX
return M
