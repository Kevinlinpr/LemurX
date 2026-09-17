-- LemurX · luakit-compatible library · theme.lua
--
-- Default theme.  Copy to luakit.config_dir .. "/theme.lua" to customise.
-- Any key left out falls back to the built-in defaults in lousy.theme.

local theme = {}

-- Fonts
theme.font = "sans 12px"
theme.sbar_font = "sans 12px"
theme.ibar_font = "monospace 12px"
theme.tab_font = "sans 12px"
theme.menu_font = "sans 12px"
theme.hint_font = "bold 11px monospace"

-- Base colours
theme.fg = "#e6e6e6"
theme.bg = "#1c1c1c"

-- Status bar notifications
theme.notif_fg = "#e6e6e6"
theme.notif_bg = "#1c1c1c"
theme.warning_fg = "#ffd75f"
theme.warning_bg = "#1c1c1c"
theme.error_fg = "#ffffff"
theme.error_bg = "#af0000"

-- Status bar / input bar
theme.sbar_fg = "#e6e6e6"
theme.sbar_bg = "#1c1c1c"
theme.ibar_fg = "#e6e6e6"
theme.ibar_bg = "#1c1c1c"

-- Load state
theme.loaded_fg = "#5faf5f"
theme.loading_fg = "#e6e6e6"
theme.loading_bg = "#1c1c1c"

-- TLS state
theme.trust_fg = "#5faf5f"
theme.notrust_fg = "#d75f5f"
theme.success_fg = "#5faf5f"

-- Scroll / zoom indicators
theme.scroll_fg = "#e6e6e6"
theme.zoom_fg = "#e6e6e6"

-- Tabs
theme.tab_fg = "#9e9e9e"
theme.tab_bg = "#262626"
theme.tab_ntheme = "#8a8a8a"
theme.tab_loading_fg = "#ffaf5f"
theme.tab_hover_bg = "#3a3a3a"
theme.tab_hover_fg = "#e6e6e6"
theme.tab_selected_fg = "#ffffff"
theme.tab_selected_bg = "#3f3f3f"
theme.tab_trust_fg = "#5faf5f"
theme.tab_notrust_fg = "#d75f5f"
theme.tablist_bg = "#1c1c1c"
theme.selected_fg = "#ffffff"
theme.selected_bg = "#3f3f3f"
theme.selected_ntheme = "#dadada"
theme.private_tab_bg = "#3a2a4a"
theme.selected_private_tab_bg = "#5a3a7a"

-- Menus (completion, tab history, undo list, ...)
theme.menu_fg = "#e6e6e6"
theme.menu_bg = "#262626"
theme.menu_selected_fg = "#ffffff"
theme.menu_selected_bg = "#005f87"
theme.menu_title_fg = "#ffffff"
theme.menu_title_bg = "#1c1c1c"
theme.menu_primary_title_fg = "#ffaf5f"
theme.menu_secondary_title_fg = "#87afd7"
theme.menu_disabled_fg = "#6c6c6c"
theme.menu_disabled_bg = "#262626"
theme.menu_enabled_fg = "#5faf5f"
theme.menu_enabled_bg = "#262626"
theme.menu_active_fg = "#ffd75f"
theme.menu_active_bg = "#262626"
theme.proxy_active_menu_fg = "#5faf5f"
theme.proxy_active_menu_bg = "#262626"
theme.proxy_inactive_menu_fg = "#9e9e9e"
theme.proxy_inactive_menu_bg = "#262626"

-- Follow-mode hints
theme.hint_fg = "#000000"
theme.hint_bg = "#ffd75f"
theme.hint_border = "1px solid #af8700"
theme.hint_opacity = "0.85"
theme.hint_overlay_bg = "rgba(255,215,95,0.25)"
theme.hint_overlay_border = "1px dotted #af8700"
theme.hint_overlay_selected_bg = "rgba(95,175,95,0.35)"
theme.hint_overlay_selected_border = "1px dotted #5faf5f"

-- Mode indicators
theme.passthrough_fg = "#e6e6e6"
theme.passthrough_bg = "#1c1c1c"
theme.insert_fg = "#e6e6e6"
theme.insert_bg = "#1c1c1c"

return theme
