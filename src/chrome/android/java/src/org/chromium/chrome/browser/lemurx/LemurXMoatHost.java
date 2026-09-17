// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.browsing_data.BrowsingDataBridge;
import org.chromium.chrome.browser.browsing_data.BrowsingDataType;
import org.chromium.chrome.browser.browsing_data.TimePeriod;
import org.chromium.chrome.browser.flags.ChromeFeatureList;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileManager;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.components.browser_ui.site_settings.WebsitePreferenceBridge;
import org.chromium.components.content_settings.ContentSetting;
import org.chromium.components.content_settings.ContentSettingsType;
import org.chromium.components.prefs.PrefService;
import org.chromium.components.user_prefs.UserPrefs;
import org.chromium.content_public.browser.LoadUrlParams;
import org.chromium.content_public.browser.NavigationController;
import org.chromium.content_public.browser.NavigationEntry;
import org.chromium.content_public.browser.NavigationHistory;
import org.chromium.content_public.browser.RenderFrameHost;
import org.chromium.content_public.browser.WebContents;
import org.chromium.url.GURL;
import org.chromium.url.Origin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * Lua 护城河：浏览器进程 HTTP、本地文件、截图、原生触控、站点权限、UA/请求头。
 * 扩展没有浏览器进程，做不到这组接口。
 */
class LemurXMoatHost {
    private static final String TAG = "LemurX";
    private static final String USER_SCRIPT_DIR = "lua";
    private static final int UGC_FETCH_MAX = 2 * 1024 * 1024;
    private static final int PRIV_FETCH_MAX = 8 * 1024 * 1024;
    private static final Map<Integer, Map<String, String>> sTabHeaders = new ConcurrentHashMap<>();
    private static final Map<Integer, String> sTabUserAgents = new ConcurrentHashMap<>();

    /**
     * 在 UI 线程同步跑一个 Callable，失败返回 null。Chromium 154 去掉了
     * ThreadUtils.runOnUiThreadBlockingNoException，runOnUiThreadBlocking(Callable) 会把
     * 任务里的异常包成 RuntimeException 抛出，这里兜住以维持旧的“出错返回 null”语义。
     */
    private static <T> T uiBlocking(Callable<T> task) {
        try {
            return ThreadUtils.runOnUiThreadBlocking(task);
        } catch (RuntimeException e) {
            Log.i(TAG, "ui thread task failed: %s", e.toString());
            return null;
        }
    }

    /** 同上，但把 null 当 false，供返回 boolean 的接口用。 */
    private static boolean uiBlockingBool(Callable<Boolean> task) {
        Boolean value = uiBlocking(task);
        return value != null && value;
    }

    static String httpFetch(String url, String optionsJson, boolean privileged) {
        JSONObject result = new JSONObject();
        HttpURLConnection conn = null;
        try {
            if (TextUtils.isEmpty(url)) {
                result.put("ok", false);
                result.put("error", "url is required");
                return result.toString();
            }
            JSONObject opt = parseObject(optionsJson);
            URL parsed = new URL(url);
            String protocol = parsed.getProtocol() == null ? "" : parsed.getProtocol().toLowerCase(Locale.US);
            if (!"http".equals(protocol) && !"https".equals(protocol)) {
                result.put("ok", false);
                result.put("error", "only http/https");
                return result.toString();
            }
            String method = opt.optString("method", "GET").toUpperCase(Locale.US);
            int timeoutCap = privileged ? 120000 : 20000;
            int timeout = Math.min(Math.max(opt.optInt("timeout", privileged ? 30000 : 15000), 1000), timeoutCap);
            int maxBody = privileged ? PRIV_FETCH_MAX : UGC_FETCH_MAX;
            conn = (HttpURLConnection) parsed.openConnection();
            conn.setInstanceFollowRedirects(opt.optBoolean("redirect", true));
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept-Encoding", "gzip");
            JSONObject headers = opt.optJSONObject("headers");
            if (headers != null) {
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    conn.setRequestProperty(key, String.valueOf(headers.opt(key)));
                }
            }
            String body = opt.optString("body", "");
            if (!TextUtils.isEmpty(body) && !"GET".equals(method) && !"HEAD".equals(method)) {
                byte[] bytes =
                        opt.optBoolean("base64", false)
                                ? Base64.decode(body, Base64.DEFAULT)
                                : body.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(bytes.length);
                conn.getOutputStream().write(bytes);
            }
            int status = conn.getResponseCode();
            InputStream raw = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] data = raw == null ? new byte[0] : readLimited(unwrapGzip(conn, raw), maxBody);
            JSONObject respHeaders = new JSONObject();
            for (int i = 0; i < 64; i++) {
                String name = conn.getHeaderFieldKey(i);
                String value = conn.getHeaderField(i);
                if (name == null && value == null) {
                    break;
                }
                if (name != null) {
                    respHeaders.put(name, value);
                }
            }
            String contentType = conn.getContentType();
            result.put("ok", status >= 200 && status < 400);
            result.put("status", status);
            result.put("headers", respHeaders);
            result.put("url", conn.getURL() == null ? url : conn.getURL().toString());
            result.put("bytes", data.length);
            if (isTextMime(contentType) && !opt.optBoolean("binary", false)) {
                result.put("body", new String(data, charsetOf(contentType)));
            } else {
                result.put("body", Base64.encodeToString(data, Base64.NO_WRAP));
                result.put("base64", true);
            }
        } catch (Exception e) {
            try {
                result.put("ok", false);
                result.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } catch (Exception ignored) {
                // 保持空对象
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return result.toString();
    }

    static String fsRoot(boolean privileged) {
        return luaRoot(privileged).getAbsolutePath();
    }

    static String fsRead(String path, String optionsJson, boolean privileged) {
        JSONObject result = new JSONObject();
        try {
            JSONObject opt = parseObject(optionsJson);
            File file = resolveLuaPath(path, privileged);
            if (!file.isFile()) {
                result.put("ok", false);
                result.put("error", "not found");
                return result.toString();
            }
            byte[] data = readFile(file, privileged ? PRIV_FETCH_MAX : UGC_FETCH_MAX);
            result.put("ok", true);
            result.put("path", relativePath(file, privileged));
            result.put("size", data.length);
            if (opt.optBoolean("base64", false)) {
                result.put("data", Base64.encodeToString(data, Base64.NO_WRAP));
                result.put("base64", true);
            } else {
                result.put("data", new String(data, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            putError(result, e);
        }
        return result.toString();
    }

    static boolean fsWrite(String path, String data, String optionsJson, boolean privileged) {
        try {
            JSONObject opt = parseObject(optionsJson);
            File file = resolveLuaPath(path, privileged);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            byte[] bytes =
                    opt.optBoolean("base64", false)
                            ? Base64.decode(data == null ? "" : data, Base64.DEFAULT)
                            : (data == null ? new byte[0] : data.getBytes(StandardCharsets.UTF_8));
            boolean append = opt.optBoolean("append", false);
            try (FileOutputStream out = new FileOutputStream(file, append)) {
                out.write(bytes);
            }
            return true;
        } catch (Exception e) {
            Log.i(TAG, "fs.write: %s", e.getMessage());
            return false;
        }
    }

    static String fsList(String path, boolean privileged) {
        JSONArray array = new JSONArray();
        try {
            File dir = TextUtils.isEmpty(path) ? luaRoot(privileged) : resolveLuaPath(path, privileged);
            File[] files = dir.listFiles();
            if (files == null) {
                return array.toString();
            }
            for (File file : files) {
                JSONObject row = new JSONObject();
                row.put("name", file.getName());
                row.put("dir", file.isDirectory());
                row.put("size", file.isFile() ? file.length() : 0);
                row.put("mtime", file.lastModified());
                array.put(row);
            }
        } catch (Exception e) {
            Log.i(TAG, "fs.list: %s", e.getMessage());
        }
        return array.toString();
    }

    static boolean fsExists(String path, boolean privileged) {
        try {
            return resolveLuaPath(path, privileged).exists();
        } catch (Exception e) {
            return false;
        }
    }

    static boolean fsMkdir(String path, boolean privileged) {
        try {
            File dir = resolveLuaPath(path, privileged);
            return dir.isDirectory() || dir.mkdirs();
        } catch (Exception e) {
            return false;
        }
    }

    static boolean fsRemove(String path, boolean privileged) {
        try {
            return deleteRecursively(resolveLuaPath(path, privileged));
        } catch (Exception e) {
            return false;
        }
    }

    static String screenshot(int tabId, String optionsJson, boolean privileged) {
        JSONObject result = new JSONObject();
        try {
            JSONObject opt = parseObject(optionsJson);
            CaptureTarget target =
                    uiBlocking(() -> CaptureTarget.from(tabId));
            if (target == null || target.view == null || target.activity == null) {
                result.put("ok", false);
                result.put("error", "no tab view");
                return result.toString();
            }
            int width = Math.max(1, target.width);
            int height = Math.max(1, target.height);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            boolean copied = copyViewPixels(target.activity, target.view, target.location, bitmap);
            if (!copied) {
                uiBlocking(
                        () -> {
                            Canvas canvas = new Canvas(bitmap);
                            target.view.draw(canvas);
                            return true;
                        });
            }
            File dir = new File(luaRoot(privileged), "captures");
            if (!dir.exists() && !dir.mkdirs()) {
                result.put("ok", false);
                result.put("error", "cannot create captures");
                return result.toString();
            }
            String name = "tab_" + target.tabId + "_" + System.currentTimeMillis() + ".jpg";
            File out = new File(dir, name);
            try (FileOutputStream stream = new FileOutputStream(out)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream);
            }
            result.put("ok", true);
            result.put("path", "captures/" + name);
            result.put("abs", out.getAbsolutePath());
            result.put("width", width);
            result.put("height", height);
            if (opt.optBoolean("base64", false)) {
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 60, encoded);
                result.put("data", Base64.encodeToString(encoded.toByteArray(), Base64.NO_WRAP));
                result.put("base64", true);
            }
            bitmap.recycle();
        } catch (Exception e) {
            putError(result, e);
        }
        return result.toString();
    }

    static boolean mute(int tabId, boolean muted) {
        return uiBlockingBool(
                () -> {
                    Tab tab = resolveTab(tabId);
                    WebContents webContents = tab == null ? null : tab.getWebContents();
                    if (webContents == null) {
                        return false;
                    }
                    webContents.setAudioMuted(muted);
                    return true;
                });
    }

    static boolean isMuted(int tabId) {
        Boolean value =
                uiBlocking(
                        () -> {
                            Tab tab = resolveTab(tabId);
                            WebContents webContents = tab == null ? null : tab.getWebContents();
                            return webContents != null && webContents.isAudioMuted();
                        });
        return value != null && value;
    }

    static boolean stop(int tabId) {
        return uiBlockingBool(
                () -> {
                    Tab tab = resolveTab(tabId);
                    WebContents webContents = tab == null ? null : tab.getWebContents();
                    if (webContents == null) {
                        return false;
                    }
                    webContents.stop();
                    return true;
                });
    }

    static String html(int tabId) {
        Tab tab = uiBlocking(() -> resolveTab(tabId));
        if (tab == null) {
            return "";
        }
        return LemurXBridge.evalJavaScript(
                tab.getId(),
                "(function(){return document.documentElement?document.documentElement.outerHTML:''})()");
    }

    static boolean setUserAgent(int tabId, String ua) {
        if (tabId <= 0) {
            Tab tab = currentTab();
            if (tab == null) {
                return false;
            }
            tabId = tab.getId();
        }
        if (TextUtils.isEmpty(ua)) {
            sTabUserAgents.remove(tabId);
        } else {
            sTabUserAgents.put(tabId, ua);
        }
        injectUserAgent(tabId);
        return true;
    }

    static boolean setHeaders(int tabId, String headersJson) {
        if (tabId <= 0) {
            Tab tab = currentTab();
            if (tab == null) {
                return false;
            }
            tabId = tab.getId();
        }
        try {
            JSONObject obj = parseObject(headersJson);
            Map<String, String> headers = new HashMap<>();
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                headers.put(key, String.valueOf(obj.opt(key)));
            }
            if (headers.isEmpty()) {
                sTabHeaders.remove(tabId);
            } else {
                sTabHeaders.put(tabId, headers);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static LoadUrlParams buildLoadUrl(int tabId, String url) {
        LoadUrlParams params = new LoadUrlParams(url);
        Map<String, String> headers = headersFor(tabId);
        if (!headers.isEmpty()) {
            params.setExtraHeaders(headers);
        }
        return params;
    }

    static void onTabEvent(String name, Tab tab) {
        if (tab == null || TextUtils.isEmpty(name)) {
            return;
        }
        if ("document".equals(name) || "loaded".equals(name) || "started".equals(name)) {
            final int id = tab.getId();
            ThreadUtils.postOnUiThread(() -> injectUserAgentOnUi(id));
        }
    }

    static boolean tap(String optionsJson) {
        return uiBlockingBool(
                () -> {
                    JSONObject opt = parseObject(optionsJson);
                    Tab tab = resolveTab(opt.optInt("tab", 0));
                    View view = tab == null ? null : tab.getView();
                    if (view == null) {
                        return false;
                    }
                    float[] xy = point(view, opt, "x", "y");
                    return dispatchTap(view, xy[0], xy[1]);
                });
    }

    static boolean swipe(String optionsJson) {
        return uiBlockingBool(
                () -> {
                    JSONObject opt = parseObject(optionsJson);
                    Tab tab = resolveTab(opt.optInt("tab", 0));
                    View view = tab == null ? null : tab.getView();
                    if (view == null) {
                        return false;
                    }
                    float[] start = point(view, opt, "x1", "y1");
                    float[] end = point(view, opt, "x2", "y2");
                    int duration = Math.max(80, opt.optInt("duration", 320));
                    return dispatchSwipe(view, start[0], start[1], end[0], end[1], duration);
                });
    }

    static boolean typeText(String text, int tabId) {
        if (text == null) {
            return false;
        }
        String script =
                "(function(t){var e=document.activeElement;if(!e)return false;"
                        + "if(e.isContentEditable){e.focus();document.execCommand('insertText',false,t);return true;}"
                        + "if(e.value===undefined)return false;var s=e.selectionStart||e.value.length;"
                        + "var n=e.selectionEnd||s;e.value=e.value.slice(0,s)+t+e.value.slice(n);"
                        + "e.dispatchEvent(new Event('input',{bubbles:true}));return true;})("
                        + JSONObject.quote(text)
                        + ")";
        String result = LemurXBridge.evalJavaScript(tabId <= 0 ? currentTabId() : tabId, script);
        return result != null && result.contains("true");
    }

    static boolean key(String name, int tabId) {
        return uiBlockingBool(
                () -> {
                    Tab tab = resolveTab(tabId);
                    View view = tab == null ? null : tab.getView();
                    if (view == null) {
                        return false;
                    }
                    int code = keyCode(name);
                    if (code == KeyEvent.KEYCODE_UNKNOWN) {
                        return false;
                    }
                    long now = SystemClock.uptimeMillis();
                    view.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
                    view.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
                    return true;
                });
    }

    static boolean permSet(String origin, String type, String value) {
        return uiBlockingBool(
                () -> {
                    Integer settingType = permType(type);
                    Integer setting = permValue(value);
                    if (settingType == null || setting == null || TextUtils.isEmpty(origin)) {
                        return false;
                    }
                    GURL url = new GURL(origin);
                    WebsitePreferenceBridge.setContentSettingDefaultScope(
                            ProfileManager.getLastUsedRegularProfile(),
                            settingType,
                            url,
                            GURL.emptyGURL(),
                            setting);
                    return true;
                });
    }

    static String permGet(String origin, String type) {
        String value =
                uiBlocking(
                        () -> {
                            Integer settingType = permType(type);
                            if (settingType == null || TextUtils.isEmpty(origin)) {
                                return "";
                            }
                            int setting =
                                    WebsitePreferenceBridge.getContentSetting(
                                            ProfileManager.getLastUsedRegularProfile(),
                                            settingType,
                                            new GURL(origin),
                                            GURL.emptyGURL());
                            return permName(setting);
                        });
        return value == null ? "" : value;
    }

    static String navHistory(int tabId) {
        String json =
                uiBlocking(
                        () -> {
                            JSONObject result = new JSONObject();
                            try {
                                Tab tab = resolveTab(tabId);
                                WebContents wc = tab == null ? null : tab.getWebContents();
                                NavigationController controller =
                                        wc == null ? null : wc.getNavigationController();
                                if (controller == null) {
                                    result.put("ok", false);
                                    result.put("error", "no navigation controller");
                                    return result.toString();
                                }
                                NavigationHistory history = controller.getNavigationHistory();
                                JSONArray entries = new JSONArray();
                                if (history != null) {
                                    for (int i = 0; i < history.getEntryCount(); i++) {
                                        NavigationEntry entry = history.getEntryAtIndex(i);
                                        if (entry == null) {
                                            continue;
                                        }
                                        JSONObject item = new JSONObject();
                                        item.put("index", entry.getIndex());
                                        item.put("url", spec(entry.getUrl()));
                                        item.put("virtualUrl", spec(entry.getVirtualUrl()));
                                        item.put("originalUrl", spec(entry.getOriginalUrl()));
                                        item.put(
                                                "title",
                                                entry.getTitle() == null ? "" : entry.getTitle());
                                        item.put("timestamp", entry.getTimestamp());
                                        item.put("transition", entry.getTransition());
                                        item.put("initial", entry.isInitialEntry());
                                        entries.put(item);
                                    }
                                    result.put("current", history.getCurrentEntryIndex());
                                }
                                result.put("ok", true);
                                result.put("entries", entries);
                                result.put("canGoBack", controller.canGoBack());
                                result.put("canGoForward", controller.canGoForward());
                            } catch (Exception e) {
                                putError(result, e);
                            }
                            return result.toString();
                        });
        return json == null ? "{\"ok\":false}" : json;
    }

    static boolean navGo(int tabId, int index) {
        Boolean ok =
                uiBlocking(
                        () -> {
                            Tab tab = resolveTab(tabId);
                            WebContents wc = tab == null ? null : tab.getWebContents();
                            NavigationController controller =
                                    wc == null ? null : wc.getNavigationController();
                            if (controller == null) {
                                return false;
                            }
                            controller.goToNavigationIndex(index);
                            return true;
                        });
        return ok != null && ok;
    }

    static boolean navOffset(int tabId, int offset) {
        Boolean ok =
                uiBlocking(
                        () -> {
                            Tab tab = resolveTab(tabId);
                            WebContents wc = tab == null ? null : tab.getWebContents();
                            NavigationController controller =
                                    wc == null ? null : wc.getNavigationController();
                            if (controller == null) {
                                return false;
                            }
                            controller.goToOffset(offset);
                            return true;
                        });
        return ok != null && ok;
    }

    static boolean reloadBypassCache(int tabId) {
        Boolean ok =
                uiBlocking(
                        () -> {
                            Tab tab = resolveTab(tabId);
                            WebContents wc = tab == null ? null : tab.getWebContents();
                            NavigationController controller =
                                    wc == null ? null : wc.getNavigationController();
                            if (controller == null) {
                                return false;
                            }
                            controller.reloadBypassingCache(true);
                            return true;
                        });
        return ok != null && ok;
    }

    static String frames(int tabId) {
        String json =
                uiBlocking(
                        () -> {
                            JSONObject result = new JSONObject();
                            try {
                                Tab tab = resolveTab(tabId);
                                WebContents wc = tab == null ? null : tab.getWebContents();
                                RenderFrameHost main = wc == null ? null : wc.getMainFrame();
                                if (main == null) {
                                    result.put("ok", false);
                                    result.put("error", "no main frame");
                                    return result.toString();
                                }
                                JSONArray list = new JSONArray();
                                List<RenderFrameHost> all = main.getAllRenderFrameHosts();
                                if (all != null) {
                                    for (RenderFrameHost rfh : all) {
                                        if (rfh == null) {
                                            continue;
                                        }
                                        JSONObject item = new JSONObject();
                                        item.put("url", spec(rfh.getLastCommittedURL()));
                                        item.put("origin", originSpec(rfh.getLastCommittedOrigin()));
                                        item.put("live", rfh.isRenderFrameLive());
                                        item.put("incognito", rfh.isIncognito());
                                        item.put("main", rfh.getMainFrame() == rfh);
                                        list.put(item);
                                    }
                                }
                                result.put("ok", true);
                                result.put("frames", list);
                            } catch (Exception e) {
                                putError(result, e);
                            }
                            return result.toString();
                        });
        return json == null ? "{\"ok\":false}" : json;
    }

    static String prefsGet(String name, String type) {
        String json =
                uiBlocking(
                        () -> {
                            JSONObject result = new JSONObject();
                            try {
                                if (TextUtils.isEmpty(name)) {
                                    result.put("ok", false);
                                    result.put("error", "pref name required");
                                    return result.toString();
                                }
                                PrefService prefs = profilePrefs();
                                result.put("ok", true);
                                result.put("name", name);
                                result.put("exists", prefs.hasPrefPath(name));
                                result.put("managed", prefs.isManagedPreference(name));
                                result.put("isDefault", prefs.isDefaultValuePreference(name));
                                String kind = type == null ? "" : type.toLowerCase(Locale.US);
                                if ("bool".equals(kind) || "boolean".equals(kind)) {
                                    result.put("value", prefs.getBoolean(name));
                                } else if ("int".equals(kind) || "integer".equals(kind)) {
                                    result.put("value", prefs.getInteger(name));
                                } else if ("long".equals(kind)) {
                                    result.put("value", prefs.getLong(name));
                                } else if ("double".equals(kind) || "number".equals(kind)) {
                                    result.put("value", prefs.getDouble(name));
                                } else if ("string".equals(kind) || "str".equals(kind)) {
                                    result.put("value", prefs.getString(name));
                                }
                            } catch (Exception e) {
                                putError(result, e);
                            }
                            return result.toString();
                        });
        return json == null ? "{\"ok\":false}" : json;
    }

    static boolean prefsSet(String name, String type, String value) {
        Boolean ok =
                uiBlocking(
                        () -> {
                            try {
                                if (TextUtils.isEmpty(name)) {
                                    return false;
                                }
                                PrefService prefs = profilePrefs();
                                String kind = type == null ? "string" : type.toLowerCase(Locale.US);
                                if ("bool".equals(kind) || "boolean".equals(kind)) {
                                    prefs.setBoolean(
                                            name,
                                            "1".equals(value)
                                                    || "true".equalsIgnoreCase(value)
                                                    || "yes".equalsIgnoreCase(value));
                                } else if ("int".equals(kind) || "integer".equals(kind)) {
                                    prefs.setInteger(name, Integer.parseInt(value));
                                } else if ("long".equals(kind)) {
                                    prefs.setLong(name, Long.parseLong(value));
                                } else if ("double".equals(kind) || "number".equals(kind)) {
                                    prefs.setDouble(name, Double.parseDouble(value));
                                } else {
                                    prefs.setString(name, value == null ? "" : value);
                                }
                                return true;
                            } catch (Exception e) {
                                Log.i(TAG, "prefs.set failed %s: %s", name, e.getMessage());
                                return false;
                            }
                        });
        return ok != null && ok;
    }

    static boolean prefsClear(String name) {
        Boolean ok =
                uiBlocking(
                        () -> {
                            try {
                                if (TextUtils.isEmpty(name)) {
                                    return false;
                                }
                                profilePrefs().clearPref(name);
                                return true;
                            } catch (Exception e) {
                                return false;
                            }
                        });
        return ok != null && ok;
    }

    static String dataClear(String typesJson, String periodName) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);
        ThreadUtils.postOnUiThread(
                () -> {
                    try {
                        Profile profile = ProfileManager.getLastUsedRegularProfile();
                        int[] types = parseDataTypes(typesJson);
                        int period = parseTimePeriod(periodName);
                        BrowsingDataBridge.getForProfile(profile)
                                .clearBrowsingData(
                                        new BrowsingDataBridge.OnClearBrowsingDataListener() {
                                            @Override
                                            public void onBrowsingDataCleared() {
                                                ok.set(true);
                                                latch.countDown();
                                            }
                                        },
                                        types,
                                        period);
                    } catch (Exception e) {
                        Log.i(TAG, "data.clear failed: %s", e.getMessage());
                        latch.countDown();
                    }
                });
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        JSONObject result = new JSONObject();
        try {
            result.put("ok", ok.get());
        } catch (Exception ignored) {
            // 保持已写入字段
        }
        return result.toString();
    }

    static boolean featureEnabled(String name) {
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        try {
            return ChromeFeatureList.isEnabled(name);
        } catch (Exception e) {
            return false;
        }
    }

    static String featureParam(String feature, String param) {
        if (TextUtils.isEmpty(feature) || TextUtils.isEmpty(param)) {
            return "";
        }
        try {
            String value = ChromeFeatureList.getFieldTrialParamByFeature(feature, param);
            return value == null ? "" : value;
        } catch (Exception e) {
            return "";
        }
    }

    private static Map<String, String> headersFor(int tabId) {
        Map<String, String> headers = new HashMap<>();
        Map<String, String> extra = sTabHeaders.get(tabId);
        if (extra != null) {
            headers.putAll(extra);
        }
        String ua = sTabUserAgents.get(tabId);
        if (!TextUtils.isEmpty(ua)) {
            headers.put("User-Agent", ua);
        }
        return headers;
    }

    private static void injectUserAgent(int tabId) {
        if (ThreadUtils.runningOnUiThread()) {
            injectUserAgentOnUi(tabId);
            return;
        }
        LemurXBridge.evalJavaScript(tabId, userAgentScript(tabId));
    }

    private static void injectUserAgentOnUi(int tabId) {
        String script = userAgentScript(tabId);
        if (TextUtils.isEmpty(script)) {
            return;
        }
        Tab tab = resolveTab(tabId);
        WebContents webContents = tab == null ? null : tab.getWebContents();
        if (webContents == null) {
            return;
        }
        webContents.evaluateJavaScript(script, null);
    }

    private static String userAgentScript(int tabId) {
        String ua = sTabUserAgents.get(tabId);
        if (TextUtils.isEmpty(ua)) {
            return "";
        }
        return "(function(ua){try{Object.defineProperty(navigator,'userAgent',"
                + "{get:function(){return ua;},configurable:true});"
                + "Object.defineProperty(navigator,'appVersion',"
                + "{get:function(){return ua;},configurable:true});}catch(e){}})("
                + JSONObject.quote(ua)
                + ")";
    }

    private static Tab resolveTab(int tabId) {
        ChromeTabbedActivity activity = LemurXBridge.currentActivity();
        if (activity == null) {
            return null;
        }
        if (tabId <= 0) {
            return activity.getActivityTab();
        }
        return LemurXBridge.findTabForLua(activity, tabId);
    }

    private static Tab currentTab() {
        ChromeTabbedActivity activity = LemurXBridge.currentActivity();
        return activity == null ? null : activity.getActivityTab();
    }

    private static int currentTabId() {
        Tab tab = currentTab();
        return tab == null ? 0 : tab.getId();
    }

    private static File luaRoot(boolean privileged) {
        File lua = new File(ContextUtils.getApplicationContext().getFilesDir(), USER_SCRIPT_DIR);
        File root = privileged ? lua : new File(lua, "ugc");
        if (!root.exists()) {
            root.mkdirs();
        }
        return root;
    }

    private static File resolveLuaPath(String path, boolean privileged) throws Exception {
        if (TextUtils.isEmpty(path)) {
            throw new IllegalArgumentException("path is required");
        }
        File root = luaRoot(privileged);
        File target = path.startsWith("/") ? new File(path) : new File(root, path);
        String canonical = target.getCanonicalPath();
        String rootPath = root.getCanonicalPath();
        if (!canonical.equals(rootPath) && !canonical.startsWith(rootPath + File.separator)) {
            throw new SecurityException("path escapes lua dir");
        }
        return target;
    }

    private static String relativePath(File file, boolean privileged) throws Exception {
        String canonical = file.getCanonicalPath();
        String root = luaRoot(privileged).getCanonicalPath();
        if (canonical.startsWith(root + File.separator)) {
            return canonical.substring(root.length() + 1);
        }
        return file.getName();
    }

    private static class CaptureTarget {
        Activity activity;
        View view;
        int tabId;
        int width;
        int height;
        final int[] location = new int[2];

        static CaptureTarget from(int tabId) {
            Tab tab = resolveTab(tabId);
            View view = tab == null ? null : tab.getView();
            ChromeTabbedActivity activity = LemurXBridge.currentActivity();
            if (tab == null || view == null || activity == null) {
                return null;
            }
            CaptureTarget target = new CaptureTarget();
            target.activity = activity;
            target.view = view;
            target.tabId = tab.getId();
            target.width = view.getWidth();
            target.height = view.getHeight();
            view.getLocationInWindow(target.location);
            return target;
        }
    }

    private static boolean copyViewPixels(
            Activity activity, View view, int[] location, Bitmap bitmap) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ThreadUtils.runningOnUiThread()) {
            return false;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return false;
        }
        Rect src =
                new Rect(
                        location[0],
                        location[1],
                        location[0] + view.getWidth(),
                        location[1] + view.getHeight());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger status = new AtomicInteger(PixelCopy.ERROR_UNKNOWN);
        try {
            PixelCopy.request(
                    window,
                    src,
                    bitmap,
                    result -> {
                        status.set(result);
                        latch.countDown();
                    },
                    new Handler(Looper.getMainLooper()));
            latch.await(3, TimeUnit.SECONDS);
            return status.get() == PixelCopy.SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    private static float[] point(View view, JSONObject opt, String xKey, String yKey) {
        float x = (float) opt.optDouble(xKey, 0);
        float y = (float) opt.optDouble(yKey, 0);
        if ("dp".equalsIgnoreCase(opt.optString("unit", "px"))) {
            DisplayMetrics metrics = view.getResources().getDisplayMetrics();
            x *= metrics.density;
            y *= metrics.density;
        }
        return new float[] {x, y};
    }

    private static boolean dispatchTap(View view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent release =
                MotionEvent.obtain(down, down + 48, MotionEvent.ACTION_UP, x, y, 0);
        boolean ok = view.dispatchTouchEvent(press);
        ok = view.dispatchTouchEvent(release) || ok;
        press.recycle();
        release.recycle();
        return ok;
    }

    private static boolean dispatchSwipe(
            View view, float x1, float y1, float x2, float y2, int duration) {
        long down = SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x1, y1, 0);
        boolean ok = view.dispatchTouchEvent(press);
        press.recycle();
        int steps = Math.max(6, duration / 16);
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            float x = x1 + (x2 - x1) * t;
            float y = y1 + (y2 - y1) * t;
            long time = down + (long) (duration * t);
            MotionEvent move = MotionEvent.obtain(down, time, MotionEvent.ACTION_MOVE, x, y, 0);
            ok = view.dispatchTouchEvent(move) || ok;
            move.recycle();
        }
        MotionEvent release =
                MotionEvent.obtain(down, down + duration, MotionEvent.ACTION_UP, x2, y2, 0);
        ok = view.dispatchTouchEvent(release) || ok;
        release.recycle();
        return ok;
    }

    private static int keyCode(String name) {
        if (TextUtils.isEmpty(name)) {
            return KeyEvent.KEYCODE_UNKNOWN;
        }
        switch (name.toLowerCase(Locale.US)) {
            case "enter":
            case "return":
                return KeyEvent.KEYCODE_ENTER;
            case "back":
                return KeyEvent.KEYCODE_BACK;
            case "tab":
                return KeyEvent.KEYCODE_TAB;
            case "space":
                return KeyEvent.KEYCODE_SPACE;
            case "esc":
            case "escape":
                return KeyEvent.KEYCODE_ESCAPE;
            case "del":
            case "delete":
            case "backspace":
                return KeyEvent.KEYCODE_DEL;
            case "home":
                return KeyEvent.KEYCODE_MOVE_HOME;
            case "end":
                return KeyEvent.KEYCODE_MOVE_END;
            default:
                try {
                    return Integer.parseInt(name);
                } catch (NumberFormatException e) {
                    return KeyEvent.KEYCODE_UNKNOWN;
                }
        }
    }

    private static Integer permType(String type) {
        if (TextUtils.isEmpty(type)) {
            return null;
        }
        switch (type.toLowerCase(Locale.US)) {
            case "geolocation":
            case "geo":
                return ContentSettingsType.GEOLOCATION;
            case "camera":
                return ContentSettingsType.MEDIASTREAM_CAMERA;
            case "microphone":
            case "mic":
                return ContentSettingsType.MEDIASTREAM_MIC;
            case "notifications":
            case "notification":
                return ContentSettingsType.NOTIFICATIONS;
            case "javascript":
            case "js":
                return ContentSettingsType.JAVASCRIPT;
            case "cookies":
            case "cookie":
                return ContentSettingsType.COOKIES;
            case "images":
            case "image":
                return ContentSettingsType.IMAGES;
            case "popups":
            case "popup":
                return ContentSettingsType.POPUPS;
            case "sound":
                return ContentSettingsType.SOUND;
            case "autoplay":
                return ContentSettingsType.AUTOPLAY;
            default:
                return null;
        }
    }

    private static Integer permValue(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        switch (value.toLowerCase(Locale.US)) {
            case "allow":
            case "grant":
            case "true":
                return ContentSetting.ALLOW;
            case "block":
            case "deny":
            case "false":
                return ContentSetting.BLOCK;
            case "ask":
            case "default":
                return ContentSetting.ASK;
            default:
                return null;
        }
    }

    private static String permName(int setting) {
        if (setting == ContentSetting.ALLOW) {
            return "allow";
        }
        if (setting == ContentSetting.BLOCK) {
            return "block";
        }
        if (setting == ContentSetting.ASK) {
            return "ask";
        }
        return "default";
    }

    private static PrefService profilePrefs() {
        return UserPrefs.get(ProfileManager.getLastUsedRegularProfile());
    }

    private static String spec(GURL url) {
        return url == null ? "" : url.getSpec();
    }

    private static String originSpec(Origin origin) {
        if (origin == null) {
            return "";
        }
        if (origin.isOpaque()) {
            return "opaque";
        }
        return origin.getScheme() + "://" + origin.getHost() + ":" + origin.getPort();
    }

    private static int[] parseDataTypes(String typesJson) {
        JSONArray arr = new JSONArray();
        if (!TextUtils.isEmpty(typesJson)) {
            try {
                String trimmed = typesJson.trim();
                if (trimmed.startsWith("[")) {
                    arr = new JSONArray(trimmed);
                } else if (trimmed.startsWith("{")) {
                    JSONObject obj = new JSONObject(trimmed);
                    JSONArray nested = obj.optJSONArray("types");
                    if (nested != null) {
                        arr = nested;
                    }
                } else {
                    arr.put(trimmed);
                }
            } catch (Exception ignored) {
                // 回退默认类型
            }
        }
        if (arr.length() == 0) {
            return new int[] {
                BrowsingDataType.HISTORY, BrowsingDataType.CACHE, BrowsingDataType.SITE_DATA
            };
        }
        int[] types = new int[arr.length()];
        int count = 0;
        for (int i = 0; i < arr.length(); i++) {
            String name = String.valueOf(arr.opt(i)).toLowerCase(Locale.US);
            Integer value = dataTypeValue(name);
            if (value != null) {
                types[count++] = value;
            }
        }
        if (count == 0) {
            return new int[] {BrowsingDataType.CACHE};
        }
        if (count == types.length) {
            return types;
        }
        int[] sliced = new int[count];
        System.arraycopy(types, 0, sliced, 0, count);
        return sliced;
    }

    private static Integer dataTypeValue(String name) {
        switch (name) {
            case "history":
                return BrowsingDataType.HISTORY;
            case "cache":
                return BrowsingDataType.CACHE;
            case "site_data":
            case "sitedata":
            case "cookies":
                return BrowsingDataType.SITE_DATA;
            case "passwords":
            case "password":
                return BrowsingDataType.PASSWORDS;
            case "form":
            case "form_data":
            case "autofill":
                return BrowsingDataType.FORM_DATA;
            case "site_settings":
            case "settings":
                return BrowsingDataType.SITE_SETTINGS;
            case "downloads":
                return BrowsingDataType.DOWNLOADS;
            case "tabs":
                return BrowsingDataType.TABS;
            default:
                return null;
        }
    }

    private static int parseTimePeriod(String periodName) {
        String name = periodName == null ? "" : periodName.toLowerCase(Locale.US).trim();
        switch (name) {
            case "hour":
            case "last_hour":
            case "1h":
                return TimePeriod.LAST_HOUR;
            case "day":
            case "last_day":
            case "1d":
                return TimePeriod.LAST_DAY;
            case "week":
            case "last_week":
            case "7d":
                return TimePeriod.LAST_WEEK;
            case "month":
            case "four_weeks":
            case "4w":
                return TimePeriod.FOUR_WEEKS;
            case "all":
            case "all_time":
                return TimePeriod.ALL_TIME;
            case "older":
            case "older_than_30_days":
                return TimePeriod.OLDER_THAN_30_DAYS;
            case "15m":
            case "15min":
            case "last_15_minutes":
                return TimePeriod.LAST_15_MINUTES;
            default:
                return TimePeriod.LAST_HOUR;
        }
    }

    private static JSONObject parseObject(String json) {
        try {
            if (TextUtils.isEmpty(json) || "null".equals(json)) {
                return new JSONObject();
            }
            return new JSONObject(json);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void putError(JSONObject result, Exception e) {
        try {
            result.put("ok", false);
            result.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } catch (Exception ignored) {
            // 保持已写入字段
        }
    }

    private static byte[] readLimited(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max) {
                throw new IllegalStateException("body too large");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] readFile(File file, int max) throws Exception {
        if (file.length() > max) {
            throw new IllegalStateException("file too large");
        }
        try (FileInputStream in = new FileInputStream(file)) {
            return readLimited(in, max);
        }
    }

    private static InputStream unwrapGzip(HttpURLConnection conn, InputStream in) throws Exception {
        String encoding = conn.getContentEncoding();
        if (encoding != null && encoding.toLowerCase(Locale.US).contains("gzip")) {
            return new GZIPInputStream(in);
        }
        return in;
    }

    private static boolean isTextMime(String contentType) {
        if (TextUtils.isEmpty(contentType)) {
            return true;
        }
        String lower = contentType.toLowerCase(Locale.US);
        return lower.contains("text/")
                || lower.contains("json")
                || lower.contains("xml")
                || lower.contains("javascript")
                || lower.contains("urlencoded");
    }

    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            int index = contentType.toLowerCase(Locale.US).indexOf("charset=");
            if (index >= 0) {
                String name = contentType.substring(index + 8).trim().replace("\"", "");
                int semi = name.indexOf(';');
                if (semi >= 0) {
                    name = name.substring(0, semi).trim();
                }
                try {
                    return Charset.forName(name);
                } catch (Exception ignored) {
                    // 回退 UTF-8
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return file.delete();
    }
}
