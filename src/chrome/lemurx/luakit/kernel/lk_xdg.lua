-- xdg 模块（对应 luakit clib/xdg.c）：XDG 目录常量
--   xdg.cache_dir xdg.config_dir xdg.data_dir
--   xdg.desktop_dir xdg.documents_dir xdg.download_dir xdg.music_dir xdg.pictures_dir
--   xdg.public_share_dir xdg.templates_dir xdg.videos_dir
--   xdg.system_data_dirs xdg.system_config_dirs（数组）
-- 未知键返回 nil（luakit 同）。Android 上用户目录映射到
-- Environment.getExternalStoragePublicDirectory(...)，三个基础目录落到应用私有目录。

local object = __lk.object
local env = ...

local dirs = (env and env.xdg) or {}
local install_dir = env and env.install_dir or ""

local values = {
    cache_dir = env and env.cache_dir or (install_dir .. "/cache"),
    config_dir = env and env.config_dir or (install_dir .. "/config"),
    data_dir = env and env.data_dir or (install_dir .. "/data"),
    desktop_dir = dirs.desktop,
    documents_dir = dirs.documents,
    download_dir = dirs.download,
    music_dir = dirs.music,
    pictures_dir = dirs.pictures,
    public_share_dir = dirs.public_share,
    templates_dir = dirs.templates,
    videos_dir = dirs.videos,
}

local props = {}
for k in pairs(values) do
    props[k] = { get = function() return values[k] end }
end
props.system_data_dirs = { get = function() return { install_dir, "/system/usr/share" } end }
props.system_config_dirs = { get = function() return { install_dir .. "/config", "/system/etc/xdg" } end }

local xdg = object.module("xdg", { props = props })

_G.xdg = xdg
return xdg
