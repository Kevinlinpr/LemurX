// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.lemurx.muse.MuseApi;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Agent 设备桥的配置：服务端地址、设备令牌、设备名、可见性开关。
 *
 * <p>来源按优先级：
 *
 * <ol>
 *   <li>SharedPreferences {@value #PREF_FILE}（将来设置页 / 登录后服务端下发的设备注册写在这里）；
 *   <li>apk 里的 {@code assets/lemurx/device.json}（部署时注入；仓库里的那份 token 为空，见
 *       chrome/lemurx/agent/device.json）；
 *   <li>服务端地址最后退回 {@link MuseApi#apiUrl()}——设备桥和对话用的是同一个 OpenMuse 服务。
 * </ol>
 *
 * <p>令牌是部署机密：不打日志、不进对话、不传给页面。
 */
public final class LemurXDeviceConfig {
    private static final String TAG = "LemurXAgent";

    static final String PREF_FILE = "lemurx_agent";
    private static final String KEY_SERVER = "server";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_ACCESS_KEY = "access_key";
    private static final String KEY_DEVICE_NAME = "device_name";
    private static final String KEY_FOREGROUND = "foreground";
    private static final String KEY_BANNER = "banner";
    private static final String KEY_SESSIONS = "sessions";

    private static final String ASSET_PATH = "lemurx/device.json";

    private static @Nullable JSONObject sAsset;

    private LemurXDeviceConfig() {}

    static SharedPreferences prefs() {
        return ContextUtils.getApplicationContext()
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    private static synchronized JSONObject asset() {
        if (sAsset != null) return sAsset;
        JSONObject out = new JSONObject();
        try (InputStream in = ContextUtils.getApplicationContext().getAssets().open(ASSET_PATH)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) buffer.write(chunk, 0, n);
            out = new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.i(TAG, "device.json: %s", e.getMessage());
        }
        sAsset = out;
        return out;
    }

    private static String pick(String key, String fallback) {
        String value = null;
        try {
            value = prefs().getString(key, null);
        } catch (Exception e) {
            // 读不到就往下退
        }
        if (TextUtils.isEmpty(value)) value = asset().optString(key, "");
        if (KEY_SERVER.equals(key) && value != null && (retired(value) || previousServer(value))) {
            if (previousServer(value)) prefs().edit().remove(KEY_SERVER).apply();
            value = "";
        }
        if (TextUtils.isEmpty(value)) value = fallback;
        return value == null ? "" : value;
    }

    /** OpenMuse 服务端根地址（不带末尾斜杠）。 */
    public static String server() {
        String url = pick(KEY_SERVER, MuseApi.apiUrl());
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    /** 上一版打包进去的公网地址。清掉之后才走现在的测服。 */
    private static boolean previousServer(String url) {
        return "https://api.aero.osmangroupx.com".equals(url);
    }

    /** 旧的明文 IP 和临时隧道已经关了。 */
    private static boolean retired(String url) {
        String lower = url.toLowerCase(java.util.Locale.US);
        return lower.contains("trycloudflare.com") || lower.contains("18.143.226.219");
    }

    /**
     * 这台手机登记后拿到的设备令牌。只认本机存的那份：apk 里旧的共享令牌服务端已经不认。
     * 空表示还没登记。
     */
    public static String token() {
        String value = null;
        try {
            value = prefs().getString(KEY_TOKEN, null);
        } catch (Exception e) {
            // 读不到就当没登记
        }
        return value == null ? "" : value.trim();
    }

    /** 登记成功后写入；传 null 清掉，下次会重新登记。 */
    public static void setDeviceToken(@Nullable String token) {
        SharedPreferences.Editor editor = prefs().edit();
        if (TextUtils.isEmpty(token)) editor.remove(KEY_TOKEN);
        else editor.putString(KEY_TOKEN, token.trim());
        editor.apply();
    }

    /**
     * 打包进去的访问密钥（服务端 .env 的 OPENMUSE_ACCESS_KEY）。仓库里的 device.json 留空，打包时注入。设置页另存的那份不在这里。
     */
    public static String packagedAccessKey() {
        return asset().optString(KEY_ACCESS_KEY, "").trim();
    }

    public static String deviceName() {
        return pick(KEY_DEVICE_NAME, "Aero");
    }

    /** agent 打开的标签切到前台（可见性契约：用户随时看得见、接得住）。 */
    public static boolean foreground() {
        return prefs().getBoolean(KEY_FOREGROUND, asset().optBoolean(KEY_FOREGROUND, true));
    }

    /** 被操作的标签顶部显示横幅。 */
    public static boolean banner() {
        return prefs().getBoolean(KEY_BANNER, asset().optBoolean(KEY_BANNER, true));
    }

    /** 服务端 / 令牌运行时覆盖（将来登录后设备注册用）；传 null 清掉覆盖。 */
    public static void setOverride(@Nullable String server, @Nullable String token) {
        SharedPreferences.Editor editor = prefs().edit();
        if (TextUtils.isEmpty(server)) editor.remove(KEY_SERVER);
        else editor.putString(KEY_SERVER, server);
        if (TextUtils.isEmpty(token)) editor.remove(KEY_TOKEN);
        else editor.putString(KEY_TOKEN, token);
        editor.apply();
    }

    static @Nullable String sessionsJson() {
        try {
            return prefs().getString(KEY_SESSIONS, null);
        } catch (Exception e) {
            return null;
        }
    }

    static void saveSessionsJson(String json) {
        prefs().edit().putString(KEY_SESSIONS, json).apply();
    }
}
