// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
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
import org.chromium.base.FileProviderUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.Nullable;
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
import org.chromium.ui.UiUtils;
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

    /**
     * 整个 Activity 窗口的截图（工具栏 + 网页 + 底栏 + 浮层），Agent 的"眼睛"。
     * 坐标系与 lemurx.ui.dump 的节点 x/y 一致（窗口在屏幕上通常从 0,0 起，返回 originX/Y 以防不是）。
     * 选项：{scale=0.5, quality=70, base64=true, privileged=bool}
     */
    static String windowScreenshot(String optionsJson) {
        JSONObject result = new JSONObject();
        try {
            JSONObject opt = parseObject(optionsJson);
            boolean privileged = opt.optBoolean("privileged", true);
            Object[] target =
                    uiBlocking(
                            () -> {
                                ChromeTabbedActivity activity = LemurXBridge.currentActivity();
                                if (activity == null || activity.getWindow() == null) {
                                    return null;
                                }
                                View decor = activity.getWindow().getDecorView();
                                if (decor == null
                                        || decor.getWidth() <= 0
                                        || decor.getHeight() <= 0) {
                                    return null;
                                }
                                int[] loc = new int[2];
                                decor.getLocationOnScreen(loc);
                                return new Object[] {activity, decor, loc};
                            });
            if (target == null) {
                result.put("ok", false);
                result.put("error", "no window");
                return result.toString();
            }
            Activity activity = (Activity) target[0];
            View decor = (View) target[1];
            int[] loc = (int[]) target[2];
            int width = Math.max(1, decor.getWidth());
            int height = Math.max(1, decor.getHeight());
            Bitmap full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            // PixelCopy 的 src 是窗口坐标，DecorView 就是窗口本身。
            boolean copied = copyViewPixels(activity, decor, new int[] {0, 0}, full);
            if (!copied) {
                uiBlocking(
                        () -> {
                            Canvas canvas = new Canvas(full);
                            decor.draw(canvas);
                            return true;
                        });
            }
            double scale = opt.optDouble("scale", 1.0);
            Bitmap bitmap = full;
            if (scale > 0 && scale < 1.0) {
                bitmap =
                        Bitmap.createScaledBitmap(
                                full,
                                Math.max(1, (int) Math.round(width * scale)),
                                Math.max(1, (int) Math.round(height * scale)),
                                true);
                full.recycle();
            } else {
                scale = 1.0;
            }
            int quality = Math.min(100, Math.max(20, opt.optInt("quality", 70)));
            File dir = new File(luaRoot(privileged), "captures");
            if (!dir.exists() && !dir.mkdirs()) {
                result.put("ok", false);
                result.put("error", "cannot create captures");
                return result.toString();
            }
            String name = "window_" + System.currentTimeMillis() + ".jpg";
            File out = new File(dir, name);
            try (FileOutputStream stream = new FileOutputStream(out)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream);
            }
            result.put("ok", true);
            result.put("path", "captures/" + name);
            result.put("abs", out.getAbsolutePath());
            result.put("width", bitmap.getWidth());
            result.put("height", bitmap.getHeight());
            result.put("scale", scale);
            result.put("originX", loc[0]);
            result.put("originY", loc[1]);
            result.put("screenWidth", width);
            result.put("screenHeight", height);
            if (opt.optBoolean("base64", false)) {
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, Math.min(quality, 60), encoded);
                result.put("data", Base64.encodeToString(encoded.toByteArray(), Base64.NO_WRAP));
                result.put("base64", true);
            }
            bitmap.recycle();
        } catch (Exception e) {
            putError(result, e);
        }
        return result.toString();
    }

    /**
     * 网页视口在屏幕上的位置与缩放，供 Agent 把 CSS 坐标换成屏幕坐标：
     *
     * <pre>
     *   screenX = x + cssX * dpr * pageScale
     *   screenY = y + contentOffsetY + cssY * dpr * pageScale
     * </pre>
     *
     * 其中 cssX/cssY 是 visualViewport 相对坐标（getBoundingClientRect 减去
     * visualViewport.offsetLeft/Top）。同一个点用于 lemurx.input.tap 时减掉 x/y 即可。
     */
    static String viewport(String optionsJson) {
        String result =
                uiBlocking(
                        () -> {
                            JSONObject out = new JSONObject();
                            JSONObject opt = parseObject(optionsJson);
                            Tab tab = resolveTab(opt.optInt("tab", 0));
                            View view = tab == null ? null : tab.getView();
                            if (tab == null || view == null) {
                                out.put("ok", false);
                                out.put("error", "no tab view");
                                return out.toString();
                            }
                            int[] loc = new int[2];
                            view.getLocationOnScreen(loc);
                            DisplayMetrics metrics = view.getResources().getDisplayMetrics();
                            out.put("ok", true);
                            out.put("tab", tab.getId());
                            out.put("x", loc[0]);
                            out.put("y", loc[1]);
                            out.put("w", view.getWidth());
                            out.put("h", view.getHeight());
                            out.put("density", metrics.density);
                            out.put("screenWidth", metrics.widthPixels);
                            out.put("screenHeight", metrics.heightPixels);
                            int contentOffsetY = 0;
                            float pageScale = 1f;
                            int scrollX = 0;
                            int scrollY = 0;
                            int viewportW = view.getWidth();
                            int viewportH = view.getHeight();
                            WebContents webContents = tab.getWebContents();
                            if (webContents != null) {
                                try {
                                    org.chromium.content_public.browser.RenderCoordinates rc =
                                            org.chromium.content_public.browser.RenderCoordinates
                                                    .fromWebContents(webContents);
                                    contentOffsetY = rc.getContentOffsetYPixInt();
                                    pageScale = rc.getPageScaleFactor();
                                    scrollX = rc.getScrollXPixInt();
                                    scrollY = rc.getScrollYPixInt();
                                    int fw = rc.getLastFrameViewportWidthPixInt();
                                    int fh = rc.getLastFrameViewportHeightPixInt();
                                    if (fw > 0) viewportW = fw;
                                    if (fh > 0) viewportH = fh;
                                } catch (Throwable t) {
                                    Log.i(TAG, "viewport render coords: %s", t.toString());
                                }
                            }
                            out.put("contentOffsetY", contentOffsetY);
                            out.put("pageScale", pageScale);
                            out.put("scrollX", scrollX);
                            out.put("scrollY", scrollY);
                            out.put("viewportWidth", viewportW);
                            out.put("viewportHeight", viewportH);
                            out.put("url", tab.getUrl() == null ? "" : tab.getUrl().getSpec());
                            out.put("title", tab.getTitle() == null ? "" : tab.getTitle());
                            return out.toString();
                        });
        return result == null ? "{\"ok\":false}" : result;
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

    /** 一条 UA 覆盖：字符串 + 客户端提示怎么发（platform 空 = 不发 Sec-CH-UA*）。 */
    private static final class UaOverride {
        final String ua;
        final String platform;
        final boolean mobile;

        UaOverride(String ua, String platform, boolean mobile) {
            this.ua = ua;
            this.platform = platform;
            this.mobile = mobile;
        }
    }

    private static final Map<Integer, UaOverride> sTabUaOverrides = new ConcurrentHashMap<>();

    /**
     * tabs.setUserAgent(id, ua[, opts])：真正改 WebContents 的 UA 覆盖（请求头、navigator.userAgent、
     * 客户端提示一起变），不是只给 tabs.navigate 加个头。opts：
     *   platform  "Windows" | "macOS" | "Linux" | "Android" | "Chrome OS" | ""（不发提示）
     *   mobile    提示里的 mobile 位（默认按 UA 里有没有 "Mobile" 猜）
     *   reload    改完是否重载（默认 true）
     * ua 空字符串 = 清除，回到 Chrome 自己的移动 / 桌面站点逻辑。
     */
    static boolean setUserAgent(int tabId, String ua, String optsJson) {
        if (tabId <= 0) {
            Tab tab = currentTab();
            if (tab == null) {
                return false;
            }
            tabId = tab.getId();
        }
        JSONObject opt = parseObject(optsJson);
        final boolean reload = opt.optBoolean("reload", true);
        if (TextUtils.isEmpty(ua)) {
            sTabUserAgents.remove(tabId);
            sTabUaOverrides.remove(tabId);
        } else {
            sTabUserAgents.put(tabId, ua);
            boolean mobile = opt.has("mobile") ? opt.optBoolean("mobile") : ua.contains("Mobile");
            sTabUaOverrides.put(tabId, new UaOverride(ua, opt.optString("platform", ""), mobile));
        }
        final int id = tabId;
        ThreadUtils.runOnUiThread(() -> applyUserAgentOnUi(resolveTab(id), reload));
        return true;
    }

    /** 兼容旧签名。 */
    static boolean setUserAgent(int tabId, String ua) {
        return setUserAgent(tabId, ua, "{}");
    }

    /** TabImpl 钩子：这个标签有没有 Lua 设的自定义 UA。 */
    public static boolean hasCustomUserAgent(@Nullable Tab tab) {
        return tab != null && sTabUaOverrides.containsKey(tab.getId());
    }

    /**
     * TabImpl 钩子：WebContents 换了（新建 / 休眠恢复）或 Chrome 想按站点设置切桌面 / 移动 UA 时，
     * 把 Lua 的自定义 UA 重新写回去。返回 true 表示已接管，Chrome 自己的切换逻辑不要再动。
     */
    public static boolean reapplyUserAgent(@Nullable Tab tab) {
        if (!hasCustomUserAgent(tab)) {
            return false;
        }
        applyUserAgentOnUi(tab, /* reload= */ false);
        return true;
    }

    private static void applyUserAgentOnUi(@Nullable Tab tab, boolean reload) {
        if (tab == null || tab.isDestroyed()) {
            return;
        }
        WebContents wc = tab.getWebContents();
        if (wc == null) {
            return;
        }
        UaOverride ov = sTabUaOverrides.get(tab.getId());
        NavigationController nc = wc.getNavigationController();
        boolean overriding = nc.getUseDesktopUserAgent();
        if (ov == null) {
            // 清除：先把原生覆盖字串清空，再让 Chrome 按站点设置重新决定桌面 / 移动
            LemurXBridge.nativeSetUserAgentOverride(wc, "", "", true);
            if (overriding) {
                nc.setUseDesktopUserAgent(false, reload, /* skipOnInitialNavigation= */ true);
            } else if (reload) {
                nc.reload(true);
            }
            return;
        }
        LemurXBridge.nativeSetUserAgentOverride(wc, ov.ua, ov.platform, ov.mobile);
        if (!overriding) {
            // 让 NavigationEntry 打上"覆盖 UA"标记；带 reload 时顺手重载
            nc.setUseDesktopUserAgent(true, reload, /* skipOnInitialNavigation= */ true);
        } else if (reload) {
            nc.reload(true);
        }
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

    /**
     * 用户关掉 Lua / 重载脚本时：清掉按 tab 记的 UA 覆盖与额外请求头。
     * 之后 tabs.navigate 不再带头，页面事件也不再重注 navigator.userAgent；
     * 已注入到当前文档里的 UA 随下一次导航消失。
     */
    static void resetAll() {
        sTabHeaders.clear();
        sTabUserAgents.clear();
        sTabUaOverrides.clear();
    }

    static void onTabEvent(String name, Tab tab) {
        if (tab == null || TextUtils.isEmpty(name)) {
            return;
        }
        // UA 现在由 WebContents 的原生覆盖负责（navigator.userAgent 跟着请求头一起变），
        // 只有原生覆盖没生效（sTabUaOverrides 里没有、只走了旧的 headersFor 路径）才补 JS。
        if ("document".equals(name) || "loaded".equals(name) || "started".equals(name)) {
            final int id = tab.getId();
            if (!sTabUaOverrides.containsKey(id)) {
                ThreadUtils.postOnUiThread(() -> injectUserAgentOnUi(id));
            }
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

    // ---------------------------------------------------------------- 分享 / 相册 / 通知

    /**
     * lemurx.share({path | text, mime, title, subject})：
     * path 是 lua 目录下的文件 → 拷到 FileProvider 覆盖的 files/images/lemurx/ 下，
     * 以 content:// 形式 ACTION_SEND 出去（微信 / 相册 / 云盘都能接）。只给 text 就是分享文字。
     */
    static boolean share(String optionsJson, boolean privileged) {
        try {
            JSONObject opt = parseObject(optionsJson);
            String path = opt.optString("path", "");
            String text = opt.optString("text", "");
            String mime = opt.optString("mime", "");
            Intent intent = new Intent(Intent.ACTION_SEND);
            if (!TextUtils.isEmpty(path)) {
                File src = resolveLuaPath(path, privileged);
                if (!src.isFile()) {
                    return false;
                }
                File dir =
                        new File(
                                UiUtils.getDirectoryForImageCapture(
                                        ContextUtils.getApplicationContext()),
                                "lemurx");
                dir.mkdirs();
                File dst = new File(dir, src.getName());
                copyFile(src, dst);
                Uri uri = FileProviderUtils.getContentUriFromFile(dst);
                if (uri == null) {
                    return false;
                }
                if (TextUtils.isEmpty(mime)) {
                    mime = guessMime(src.getName());
                }
                intent.setType(mime);
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.setClipData(android.content.ClipData.newRawUri("", uri));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                intent.setType(TextUtils.isEmpty(mime) ? "text/plain" : mime);
            }
            if (!TextUtils.isEmpty(text)) {
                intent.putExtra(Intent.EXTRA_TEXT, text);
            }
            String subject = opt.optString("subject", "");
            if (!TextUtils.isEmpty(subject)) {
                intent.putExtra(Intent.EXTRA_SUBJECT, subject);
            }
            final Intent chooser = Intent.createChooser(intent, opt.optString("title", "分享"));
            ThreadUtils.runOnUiThread(
                    () -> {
                        Activity activity = LemurXBridge.currentActivity();
                        if (activity != null) {
                            activity.startActivity(chooser);
                        } else {
                            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ContextUtils.getApplicationContext().startActivity(chooser);
                        }
                    });
            return true;
        } catch (Exception e) {
            Log.i(TAG, "share: %s", e.toString());
            return false;
        }
    }

    /**
     * lemurx.media.save({path, name, mime, album})：把 lua 目录下的文件存进系统相册 / 下载
     * （MediaStore，Android 10+ 不需要存储权限）。图片 → Pictures/<album>，其它 → Download/<album>。
     * 返回 {ok, uri, name}。
     */
    static String mediaSave(String optionsJson, boolean privileged) {
        JSONObject out = new JSONObject();
        try {
            JSONObject opt = parseObject(optionsJson);
            File src = resolveLuaPath(opt.optString("path", ""), privileged);
            if (!src.isFile()) {
                throw new IllegalArgumentException("file not found");
            }
            String name = opt.optString("name", src.getName());
            String mime = opt.optString("mime", guessMime(name));
            String album = opt.optString("album", "LemurX");
            boolean image = mime.startsWith("image/");
            boolean video = mime.startsWith("video/");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                throw new UnsupportedOperationException("Android 10+ only");
            }
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
            String relative =
                    (image
                                    ? android.os.Environment.DIRECTORY_PICTURES
                                    : video
                                            ? android.os.Environment.DIRECTORY_MOVIES
                                            : android.os.Environment.DIRECTORY_DOWNLOADS)
                            + "/"
                            + album;
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relative);
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection =
                    image
                            ? android.provider.MediaStore.Images.Media.getContentUri(
                                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                            : video
                                    ? android.provider.MediaStore.Video.Media.getContentUri(
                                            android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                                    : android.provider.MediaStore.Downloads.getContentUri(
                                            android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
            android.content.ContentResolver resolver =
                    ContextUtils.getApplicationContext().getContentResolver();
            Uri uri = resolver.insert(collection, values);
            if (uri == null) {
                throw new IllegalStateException("MediaStore insert failed");
            }
            try (InputStream in = new FileInputStream(src);
                    java.io.OutputStream os = resolver.openOutputStream(uri)) {
                if (os == null) {
                    throw new IllegalStateException("cannot open output");
                }
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            values.clear();
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            out.put("ok", true);
            out.put("uri", uri.toString());
            out.put("name", name);
            out.put("path", relative + "/" + name);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("error", String.valueOf(e));
            } catch (Exception ignored) {
                // 保持已写入字段
            }
        }
        return out.toString();
    }

    private static final String NOTIFY_CHANNEL = "lemurx_lua";

    /**
     * lemurx.notify.show({id, title, text, url, ongoing, silent})：系统通知；点开在 LemurX 里打开 url。
     * Android 13+ 没拿到通知权限时返回 false（Lua 侧退回 toast）。
     */
    static boolean notify(String optionsJson) {
        try {
            JSONObject opt = parseObject(optionsJson);
            android.content.Context ctx = ContextUtils.getApplicationContext();
            android.app.NotificationManager nm =
                    (android.app.NotificationManager)
                            ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (nm == null || !nm.areNotificationsEnabled()) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && nm.getNotificationChannel(NOTIFY_CHANNEL) == null) {
                android.app.NotificationChannel channel =
                        new android.app.NotificationChannel(
                                NOTIFY_CHANNEL,
                                "Lua 脚本",
                                android.app.NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(channel);
            }
            int id = opt.optInt("id", (int) (SystemClock.uptimeMillis() & 0x7fffffff));
            android.app.Notification.Builder b =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? new android.app.Notification.Builder(ctx, NOTIFY_CHANNEL)
                            : new android.app.Notification.Builder(ctx);
            b.setContentTitle(opt.optString("title", "LemurX"))
                    .setContentText(opt.optString("text", ""))
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(!opt.optBoolean("ongoing", false))
                    .setOngoing(opt.optBoolean("ongoing", false))
                    .setOnlyAlertOnce(true);
            String text = opt.optString("text", "");
            if (text.length() > 40) {
                b.setStyle(new android.app.Notification.BigTextStyle().bigText(text));
            }
            String url = opt.optString("url", "");
            if (!TextUtils.isEmpty(url)) {
                Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                open.setPackage(ctx.getPackageName());
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                b.setContentIntent(
                        android.app.PendingIntent.getActivity(
                                ctx,
                                id,
                                open,
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                        | android.app.PendingIntent.FLAG_IMMUTABLE));
            }
            nm.notify(id, b.build());
            return true;
        } catch (Exception e) {
            Log.i(TAG, "notify: %s", e.toString());
            return false;
        }
    }

    static boolean notifyCancel(int id) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager)
                            ContextUtils.getApplicationContext()
                                    .getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return false;
            }
            nm.cancel(id);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src);
                FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static String guessMime(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".txt") || lower.endsWith(".lua") || lower.endsWith(".md")) {
            return "text/plain";
        }
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
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
            // LemurX 官方脚本用到的更多站点设置（暗色 / 桌面版 / 广告 / 剪贴板 / 传感器 …）
            case "auto_dark":
            case "dark":
                return ContentSettingsType.AUTO_DARK_WEB_CONTENT;
            case "desktop_site":
            case "desktop":
                return ContentSettingsType.REQUEST_DESKTOP_SITE;
            case "ads":
                return ContentSettingsType.ADS;
            case "clipboard":
                return ContentSettingsType.CLIPBOARD_READ_WRITE;
            case "sensors":
                return ContentSettingsType.SENSORS;
            case "background_sync":
                return ContentSettingsType.BACKGROUND_SYNC;
            case "midi":
                return ContentSettingsType.MIDI_SYSEX;
            case "nfc":
                return ContentSettingsType.NFC;
            case "vr":
                return ContentSettingsType.VR;
            case "ar":
                return ContentSettingsType.AR;
            case "storage_access":
                return ContentSettingsType.STORAGE_ACCESS;
            case "idle_detection":
                return ContentSettingsType.IDLE_DETECTION;
            case "protected_media":
                return ContentSettingsType.PROTECTED_MEDIA_IDENTIFIER;
            case "javascript_jit":
            case "jit":
                return ContentSettingsType.JAVASCRIPT_JIT;
            case "federated_identity":
            case "fedcm":
                return ContentSettingsType.FEDERATED_IDENTITY_API;
            case "anti_abuse":
                return ContentSettingsType.ANTI_ABUSE;
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
