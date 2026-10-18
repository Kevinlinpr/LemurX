// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;

/**
 * 给设置页每一行补一个前置图标：Aero 自己的项在创建时就指定，点进去的 Chromium
 * 二级 / 三级菜单（密码、隐私、全部设置…）多数 XML 没有 icon，这里按 key 补上，
 * 对不上的用通用设置图标，这样每一层看起来都整齐。
 */
@NullMarked
public final class LemurXSettingsIcons {
    private LemurXSettingsIcons() {}

    /** 给 {@code group} 里还没有图标的项补上图标（含嵌套分组）。 */
    public static void apply(@Nullable PreferenceGroup group) {
        if (group == null) return;
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference instanceof PreferenceGroup) {
                apply((PreferenceGroup) preference);
                if (preference instanceof PreferenceCategory) continue;
            }
            if (preference.getIcon() != null) continue;
            int icon = iconFor(preference.getKey());
            if (icon == 0) continue;
            preference.setIcon(icon);
            preference.setIconSpaceReserved(true);
        }
    }

    static void set(Preference preference, int icon) {
        preference.setIcon(icon);
        preference.setIconSpaceReserved(true);
    }

    private static int iconFor(@Nullable String key) {
        if (key == null) return R.drawable.lemurx_muse_ic_settings;
        switch (key) {
            case "lemurx_account":
            case "sign_in":
                return R.drawable.lemurx_muse_ic_user;
            case "lemurx_sign_out":
                return R.drawable.lemurx_muse_ic_logout;
            case "lemurx_data":
                return R.drawable.lemurx_muse_ic_sparkles;
            case "lemurx_autofill":
            case "autofill_and_passwords":
            case "passwords":
            case "password_leak_detection":
            case "lemurx_agent_key":
                return R.drawable.lemurx_muse_ic_key;
            case "autofill_payment_methods":
            case "can_make_payment":
                return R.drawable.lemurx_muse_ic_card;
            case "autofill_addresses":
            case "autofill_options":
                return R.drawable.lemurx_muse_ic_pin;
            case "lemurx_search_engine":
            case "search_engine":
                return R.drawable.lemurx_console_ic_search;
            case "lemurx_privacy":
            case "privacy":
            case "safety_hub":
            case "safe_browsing":
            case "advanced_protection_info":
                return R.drawable.lemurx_muse_ic_shield;
            case "lemurx_all_settings":
            case "lemurx_agent_reconnect":
                return key.equals("lemurx_agent_reconnect")
                        ? R.drawable.lemurx_muse_ic_refresh
                        : R.drawable.lemurx_muse_ic_settings;
            case "lemurx_version":
            case "about_chrome":
                return R.drawable.lemurx_muse_ic_info;
            case "lemurx_account_gateway":
            case "lemurx_agent_url":
            case "google_services":
                return key.equals("google_services")
                        ? R.drawable.lemurx_muse_ic_cloud
                        : R.drawable.lemurx_muse_ic_server;
            case "clear_browsing_data":
                return R.drawable.lemurx_muse_ic_trash;
            case "notifications":
                return R.drawable.lemurx_muse_ic_bell;
            case "homepage":
                return R.drawable.lemurx_muse_ic_home;
            case "downloads":
                return R.drawable.lemurx_muse_ic_download;
            case "appearance":
            case "address_bar":
                return key.equals("appearance")
                        ? R.drawable.lemurx_muse_ic_palette
                        : R.drawable.lemurx_console_ic_web;
            case "languages":
                return R.drawable.lemurx_muse_ic_languages;
            case "accessibility":
                return R.drawable.lemurx_muse_ic_accessibility;
            case "developer":
                return R.drawable.lemurx_muse_ic_code;
            case "tabs":
                return R.drawable.lemurx_console_ic_tabs;
            case "content_settings":
            case "javascript_optimizer":
            case "third_party_cookies":
                return R.drawable.lemurx_console_ic_web;
            case "do_not_track":
            case "universal_opt_out":
            case "privacy_guide":
                return R.drawable.lemurx_muse_ic_eye_off;
            case "https_first_mode":
            case "https_first_mode_legacy":
            case "secure_dns":
            case "incognito_lock":
                return R.drawable.lemurx_muse_ic_lock;
            case "preload_pages":
            case "default_browser":
            case "glic":
            default:
                return R.drawable.lemurx_muse_ic_settings;
        }
    }
}
