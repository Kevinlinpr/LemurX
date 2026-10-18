// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import org.chromium.base.ActivityState;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.lemurx.LemurXConsoleShell;
import org.chromium.chrome.browser.lemurx.LemurXKillSwitch;
import org.chromium.chrome.browser.lemurx.muse.MuseUrls;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabLaunchType;
import org.chromium.chrome.browser.tab.TabSelectionType;
import org.chromium.chrome.browser.tabmodel.TabClosureParams;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.tabmodel.TabModelUtils;
import org.chromium.content_public.browser.LoadUrlParams;
import org.chromium.content_public.browser.RenderCoordinates;
import org.chromium.content_public.browser.WebContents;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 设备桥：把这台 LemurX 变成 OpenMuse 的浏览器 worker。
 *
 * <p>原理：OpenMuse 服务端的 BrowserService 只认 worker 的 REST 语义
 * （POST /sessions · POST /sessions/:id/navigate|close|input · GET /sessions/:id/read|screenshot|downloads）。
 * 手机在 NAT 后、只能出站，所以服务端加了一个桥（apps/server/src/lemurx-bridge.ts）：
 * BrowserService 照常 fetch 到 /lemurx/worker/*，桥把它排成命令；这里长轮询
 * GET /lemurx/device/poll 把命令取回来，在本机标签页上执行，再 POST /lemurx/device/reply 回包。
 *
 * <p>会话 = 本机的一个标签页。session id（UUID）→ tab id 的映射持久化在 SharedPreferences，
 * 标签被用户关掉就按原 URL 重开。Agent 是盖在所有标签上的控制台：手势打到当前前台的网页，
 * 用户自己打开的标签也算；对话页（chrome-native://lemurx/...）自己永远不会被导航或点击。
 *
 * <p>前台时的操作就是真人操作：点击 / 滑动是带压力、弧线和抖动的真实触摸事件，文字走输入法通道
 * （{@link LemurXHumanInput}），每一下都画在屏幕上（{@link LemurXTouchOverlay}）；用户一碰屏幕，
 * 进行中的手势立即取消。窗口不在前台时一律回 DEVICE_BACKGROUND，由服务端转给云端浏览器。
 *
 * <p>用户永远赢：
 *
 * <ul>
 *   <li>令牌为空就什么都不做；连接期间有常驻通知；被操作的标签顶部有横幅和「停止」。
 *   <li>只允许 http(s) 导航；chrome:// chrome-native:// file:// 等一律拒绝。
 *   <li>页面文本只是数据：读到的内容原样交给服务端，这里不解释、不执行。
 * </ul>
 *
 * <p>读页面不走脚本注入也不走 DevTools：正文和可操作元素来自渲染进程的无障碍树
 * （{@link LemurXPage}），截图是合成器表面的像素拷贝。页面侧没有任何"被自动化"的可观察痕迹
 * （没有 debugger 附着、没有 Runtime.evaluate、没有 CDP 的 navigator 特征）。
 *
 * <p>点击 / 打字除了 1280x800 画布坐标，还接受 {@code target}（元素 id 或可见文字）：落指那一刻
 * 重新拍快照定位，目标不在视口就先滚过去，太小就先双指放大——页面聚焦后自动缩放、滚动造成的
 * 坐标漂移由此消解。网页之外的原生对话框（JS 对话框、select、日期时间、长按菜单）由
 * {@link LemurXDialogs} 报告和回答。
 *
 * <p>线程模型：一条后台线程跑轮询 + HTTP；每条命令在它上面串行执行，需要碰标签页的步骤用
 * {@link #onUi} 切到 UI 线程并等结果（带超时），所以 UI 线程从不阻塞。这是从旧的 Lua 版本
 * （openmuse.lua）逐函数移植过来的，REST 语义保持一致。
 */
public final class LemurXDeviceBridge {
    private static final String TAG = "LemurXAgent";

    // 与 OpenMuse 侧对齐：BrowserService.request() 45s 超时，桥 40s，本机每条命令预算 30s。
    private static final long COMMAND_BUDGET_MS = 30_000;
    private static final int POLL_WAIT_S = 25;
    private static final int POLL_TIMEOUT_MS = 40_000;
    private static final int HTTP_TIMEOUT_MS = 15_000;
    private static final long UI_TIMEOUT_MS = 10_000;
    /** 无障碍树快照最多序列化这么多节点（正文 + 元素都从里面来）。 */
    private static final int AX_MAX_NODES = 5_000;
    /** browse_web 打开后先等加载，再拍无障碍树；超时也继续读，避免一直停在"正在打开"。 */
    private static final long LOAD_WAIT_MS = 18_000;
    private static final long LOAD_POLL_MS = 200;
    private static final long LOAD_SETTLE_MS = 450;
    private static final long BACKOFF_MIN_MS = 2_000;
    private static final long BACKOFF_MAX_MS = 30_000;
    // OpenMuse 的接管控制台把点击换算到 1280x800 的虚拟画布上（browser-console.ts），
    // 这里再按比例映射回本机视口的 CSS 像素。
    private static final double CONSOLE_W = 1280;
    private static final double CONSOLE_H = 800;

    private static final int NOTIFY_ID = 7301;
    private static final String NOTIFY_CHANNEL = "lemurx_agent";

    private static final Pattern SESSION_PATH =
            Pattern.compile("^/sessions/([^/]+)/([A-Za-z]+)/?([^/]*)$");
    private static final Pattern SESSION_ID = Pattern.compile("^[0-9A-Fa-f-]+$");

    /** 一个会话：服务端的 session id 对应本机的一个标签页。 */
    private static final class Session {
        final String id;
        int tab = Tab.INVALID_TAB_ID;
        String url = "";
        String title = "";
        String status = "idle";
        String updatedAt = nowIso();

        Session(String id) {
            this.id = id;
        }

        JSONObject view() {
            JSONObject out = new JSONObject();
            try {
                out.put("id", id);
                out.put("title", title == null ? "" : title);
                out.put("url", url == null ? "" : url);
                out.put("status", status == null ? "idle" : status);
                out.put("updatedAt", updatedAt == null ? nowIso() : updatedAt);
            } catch (Exception ignored) {
                // 键是常量
            }
            return out;
        }

        JSONObject persist() {
            JSONObject out = view();
            try {
                out.put("tab", tab);
            } catch (Exception ignored) {
                // 键是常量
            }
            return out;
        }

        static Session from(JSONObject json) {
            Session s = new Session(json.optString("id", ""));
            s.tab = json.optInt("tab", Tab.INVALID_TAB_ID);
            s.url = json.optString("url", "");
            s.title = json.optString("title", "");
            s.status = json.optString("status", "idle");
            s.updatedAt = json.optString("updatedAt", nowIso());
            return s;
        }
    }

    /** 回给服务端的一条应答（worker REST 回包）。 */
    private static final class Reply {
        int status = 500;
        @Nullable Object json; // JSONObject / JSONArray
        @Nullable String raw; // 已编码好的 JSON 文本
        @Nullable String contentType;
        @Nullable String bodyBase64;

        static Reply json(int status, Object json) {
            Reply r = new Reply();
            r.status = status;
            r.json = json;
            return r;
        }

        static Reply raw(int status, String raw) {
            Reply r = new Reply();
            r.status = status;
            r.raw = raw;
            return r;
        }

        static Reply binary(String contentType, String base64) {
            Reply r = new Reply();
            r.status = 200;
            r.contentType = contentType;
            r.bodyBase64 = base64;
            return r;
        }

        static Reply error(int status, String code, String message) {
            JSONObject error = new JSONObject();
            JSONObject body = new JSONObject();
            try {
                error.put("code", code);
                error.put("message", message);
                body.put("error", error);
            } catch (Exception ignored) {
                // 键是常量
            }
            return json(status, body);
        }
    }

    /** 会话操作失败时抛出，携带 worker 语义的错误码。 */
    private static final class SessionError extends Exception {
        final int status;
        final String code;

        SessionError(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        Reply reply() {
            return Reply.error(status, code, getMessage());
        }
    }

    private static final class HttpResult {
        int status;
        String body = "";
        @Nullable String error;
    }

    // ---------------------------------------------------------------- 状态

    private static @Nullable ChromeTabbedActivity sActivity;
    private static volatile int sGeneration;
    private static @Nullable Thread sThread;
    private static volatile boolean sConnected;
    private static volatile @Nullable String sLastError;
    private static volatile long sLastPollMs;
    private static volatile int sCommands;
    /** 用户点了「接管」：本次进程内不再连接，重新打开 Aero 后恢复。 */
    private static volatile boolean sPausedByUser;

    private static @Nullable Map<String, Session> sSessions;
    private static @Nullable View sBanner;
    private static @Nullable LemurXTouchOverlay sOverlay;
    private static @Nullable LemurXAgentKeyboard sKeyboard;
    private static final Runnable sHideKeyboard = LemurXDeviceBridge::hideKeyboardNow;
    private static int sBannerTab = Tab.INVALID_TAB_ID;

    private LemurXDeviceBridge() {}

    // ---------------------------------------------------------------- 挂接

    /** ChromeTabbedActivity 原生初始化完成后（UI 线程），由 LemurXConsoleShell.attach 调入。 */
    public static void attach(ChromeTabbedActivity activity) {
        ThreadUtils.assertOnUiThread();
        if (activity.isDestroyed() || activity.isFinishing()) return;
        if (sActivity == activity && sThread != null && sThread.isAlive()) return;
        sActivity = activity;
        overlay(); // 提前挂好并完成布局，第一下点击就能画在正确位置
        start();
    }

    /** 窗口销毁：停轮询、收横幅、撤通知。UI 线程。 */
    public static void detach(ChromeTabbedActivity activity) {
        ThreadUtils.assertOnUiThread();
        if (sActivity != activity) return;
        stop();
        hideBanner();
        sActivity = null;
    }

    /** 测试包被远程关停：停轮询，本次进程不再连。UI 线程。 */
    public static void haltFromKill() {
        if (!ThreadUtils.runningOnUiThread()) {
            ThreadUtils.postOnUiThread(LemurXDeviceBridge::haltFromKill);
            return;
        }
        sPausedByUser = true;
        stop();
    }

    /** 配置变了（服务端 / 令牌）：重连。UI 线程。 */
    public static void restart() {
        ThreadUtils.assertOnUiThread();
        stop();
        sPausedByUser = false;
        if (sActivity != null) start();
    }

    /** 设置页 / 调试用的状态快照。 */
    public static JSONObject status() {
        JSONObject out = new JSONObject();
        try {
            out.put("running", sThread != null && sThread.isAlive());
            out.put("connected", sConnected);
            out.put("lastError", sLastError);
            out.put("lastPoll", sLastPollMs);
            out.put("commands", sCommands);
            out.put("pausedByUser", sPausedByUser);
            out.put("configured", !TextUtils.isEmpty(LemurXDeviceConfig.token()));
        } catch (Exception ignored) {
            // 键是常量
        }
        return out;
    }

    private static void start() {
        if (sPausedByUser || LemurXKillSwitch.isDisabled()) return;
        String token = LemurXDeviceConfig.token();
        if (TextUtils.isEmpty(token)) {
            Log.i(TAG, "no device token; bridge idle");
            return;
        }
        final int generation = ++sGeneration;
        Thread thread =
                new Thread(() -> loop(generation), "LemurXAgentBridge");
        thread.setDaemon(true);
        sThread = thread;
        thread.start();
        Log.i(TAG, "bridge started -> %s", LemurXDeviceConfig.server());
    }

    private static void stop() {
        hideKeyboard();
        sGeneration++;
        Thread thread = sThread;
        sThread = null;
        if (thread != null) thread.interrupt();
        setConnected(false);
    }

    private static void setConnected(boolean connected) {
        if (sConnected == connected) return;
        sConnected = connected;
        ThreadUtils.runOnUiThread(() -> notifyConnected(connected));
    }

    // ---------------------------------------------------------------- 轮询循环（后台线程）

    private static void loop(int generation) {
        long backoff = BACKOFF_MIN_MS;
        while (sGeneration == generation && !Thread.currentThread().isInterrupted()) {
            String url;
            try {
                url =
                        LemurXDeviceConfig.server()
                                + "/lemurx/device/poll?wait="
                                + POLL_WAIT_S
                                + "&fg="
                                + (foreground() ? "1" : "0")
                                + "&name="
                                + URLEncoder.encode(LemurXDeviceConfig.deviceName(), "UTF-8");
            } catch (Exception e) {
                url = LemurXDeviceConfig.server() + "/lemurx/device/poll?wait=" + POLL_WAIT_S;
            }
            HttpResult r = http("GET", url, null, POLL_TIMEOUT_MS);
            if (sGeneration != generation) break;
            sLastPollMs = System.currentTimeMillis();
            if (r.status == 200) {
                if (!sConnected) Log.i(TAG, "connected to %s", LemurXDeviceConfig.server());
                setConnected(true);
                sLastError = null;
                backoff = BACKOFF_MIN_MS;
                JSONArray commands = null;
                try {
                    commands = new JSONObject(r.body).optJSONArray("commands");
                } catch (Exception e) {
                    Log.i(TAG, "poll body: %s", e.getMessage());
                }
                if (commands != null) {
                    for (int i = 0; i < commands.length() && sGeneration == generation; i++) {
                        JSONObject cmd = commands.optJSONObject(i);
                        if (cmd != null) handle(cmd);
                    }
                }
                continue;
            }
            if (LemurXKillSwitch.isDisabledStatus(r.status, r.body)) {
                Log.i(TAG, "test build disabled by server");
                LemurXKillSwitch.activate();
                break;
            }
            String why =
                    r.status == 401
                            ? "device token rejected (401)"
                            : r.status == 404
                                    ? "server has no Aero bridge (404)"
                                    : r.error != null ? r.error : "http " + r.status;
            if (sConnected || !why.equals(sLastError)) Log.i(TAG, "poll failed: %s", why);
            setConnected(false);
            sLastError = why;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                break;
            }
            backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
        }
        if (sGeneration == generation) setConnected(false);
    }

    private static void handle(JSONObject cmd) {
        sCommands++;
        long started = SystemClock.uptimeMillis();
        Reply result;
        try {
            result = dispatch(cmd);
        } catch (Throwable t) {
            Log.w(TAG, "command %s %s crashed", cmd.optString("method"),
                    cmd.optString("path"), t);
            result = Reply.error(500, "WORKER_FAILURE",
                    "The browser operation failed on the device.");
        }
        if (SystemClock.uptimeMillis() - started > COMMAND_BUDGET_MS) {
            Log.i(TAG, "%s %s took longer than the budget", cmd.optString("method"),
                    cmd.optString("path"));
        }
        HttpResult r =
                http("POST", LemurXDeviceConfig.server() + "/lemurx/device/reply",
                        encodeReply(cmd.optString("id"), result), HTTP_TIMEOUT_MS);
        if (r.status < 200 || r.status >= 300) {
            Log.i(TAG, "reply %s failed: %s", cmd.optString("id"),
                    r.error != null ? r.error : String.valueOf(r.status));
        }
    }

    /** 回包信封手工拼：json 走 JSONObject，raw 是已编码好的 JSON 文本，bodyBase64 是二进制。 */
    private static String encodeReply(String id, Reply result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":").append(JSONObject.quote(id)).append(",\"status\":")
                .append(result.status);
        if (result.contentType != null) {
            sb.append(",\"contentType\":").append(JSONObject.quote(result.contentType));
        }
        if (result.bodyBase64 != null) {
            sb.append(",\"bodyBase64\":").append(JSONObject.quote(result.bodyBase64));
        } else if (result.raw != null) {
            sb.append(",\"json\":").append(result.raw);
        } else if (result.json != null) {
            sb.append(",\"json\":").append(result.json.toString());
        }
        sb.append('}');
        return sb.toString();
    }

    private static HttpResult http(String method, String url, @Nullable String body,
            int timeoutMs) {
        HttpResult out = new HttpResult();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(timeoutMs);
            connection.setUseCaches(false);
            connection.setRequestProperty("Authorization",
                    "Bearer " + LemurXDeviceConfig.token());
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(bytes);
                }
            }
            out.status = connection.getResponseCode();
            InputStream in =
                    out.status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (in != null) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) buffer.write(chunk, 0, n);
                in.close();
                out.body = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            out.status = 0;
            out.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            if (connection != null) connection.disconnect();
        }
        return out;
    }

    // ---------------------------------------------------------------- 命令分发（worker REST 语义）

    private static Reply dispatch(JSONObject cmd) {
        String method = cmd.optString("method", "GET");
        String path = cmd.optString("path", "/");
        JSONObject body = cmd.optJSONObject("body");
        if (body == null) body = new JSONObject();
        try {
            // 可见性契约：窗口不在前台时不打开、不读、不操作网页（用户看不见就不做）。
            // 服务端收到 DEVICE_BACKGROUND 会把这条会话交给云端浏览器。
            boolean touchesPage = path.startsWith("/sessions")
                    && !("GET".equals(method) && "/sessions".equals(path))
                    && !path.endsWith("/close") && !path.contains("/downloads");
            if (touchesPage && !foreground()) {
                return Reply.error(409, "DEVICE_BACKGROUND",
                        "Aero is not on screen; the user cannot see the page.");
            }
            if ("/sessions".equals(path) && "GET".equals(method)) {
                JSONArray list = listSessions();
                return list.length() == 0 ? Reply.raw(200, "[]") : Reply.json(200, list);
            }
            if ("/sessions".equals(path) && "POST".equals(method)) {
                String id = body.optString("id", "");
                if (TextUtils.isEmpty(id) || !SESSION_ID.matcher(id).matches()) {
                    return Reply.error(400, "INVALID_SESSION", "A session id is required.");
                }
                return Reply.json(201, openSession(id, body.optString("url", "")));
            }
            Matcher m = SESSION_PATH.matcher(path);
            if (!m.matches()) {
                return Reply.error(404, "NOT_FOUND", "Worker endpoint not found.");
            }
            String id = m.group(1);
            String action = m.group(2);
            String extra = m.group(3) == null ? "" : m.group(3);
            if ("navigate".equals(action) && "POST".equals(method)) {
                return Reply.json(200, openSession(id, body.optString("url", "")));
            } else if ("close".equals(action) && "POST".equals(method)) {
                return Reply.json(200, closeSession(id));
            } else if ("read".equals(action) && "GET".equals(method)) {
                return Reply.json(200, readSession(id));
            } else if ("elements".equals(action) && "GET".equals(method)) {
                return Reply.json(200, elementsSession(id));
            } else if ("screenshot".equals(action) && "GET".equals(method)) {
                return Reply.binary("image/jpeg", screenshotSession(id));
            } else if ("input".equals(action) && "POST".equals(method)) {
                return Reply.json(200, inputSession(id, body));
            } else if ("downloads".equals(action) && "GET".equals(method)) {
                // 真机下载落在用户手机上；向服务端导出文件需要单独授权，暂不提供。
                if (!extra.isEmpty()) {
                    return Reply.error(404, "DOWNLOAD_NOT_FOUND",
                            "Downloads are not exported from this device.");
                }
                return Reply.raw(200, "{\"downloads\":[],\"failures\":[]}");
            }
            return Reply.error(404, "NOT_FOUND", "Worker endpoint not found.");
        } catch (SessionError e) {
            return e.reply();
        }
    }

    // ---------------------------------------------------------------- 会话

    private static synchronized Map<String, Session> sessions() {
        if (sSessions != null) return sSessions;
        Map<String, Session> map = new HashMap<>();
        try {
            String json = LemurXDeviceConfig.sessionsJson();
            if (json != null) {
                JSONObject root = new JSONObject(json);
                Iterator<String> keys = root.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject item = root.optJSONObject(key);
                    if (item != null) map.put(key, Session.from(item));
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "sessions: %s", e.getMessage());
        }
        sSessions = map;
        return map;
    }

    private static synchronized void saveSessions() {
        if (sSessions == null) return;
        JSONObject root = new JSONObject();
        try {
            for (Map.Entry<String, Session> entry : sSessions.entrySet()) {
                root.put(entry.getKey(), entry.getValue().persist());
            }
        } catch (Exception ignored) {
            // 键是 session id
        }
        LemurXDeviceConfig.saveSessionsJson(root.toString());
    }

    private static JSONArray listSessions() {
        JSONArray out = new JSONArray();
        for (Session s : sessions().values()) out.put(s.view());
        return out;
    }

    private static boolean allowedUrl(@Nullable String url) {
        return url != null
                && url.length() <= 8192
                && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private static JSONObject openSession(String id, String url) throws SessionError {
        if (!allowedUrl(url)) {
            throw new SessionError(400, "INVALID_URL",
                    "Only http(s) URLs can be opened on this device.");
        }
        Map<String, Session> sessions = sessions();
        Session s = sessions.get(id);
        if (s == null) s = new Session(id);
        final Session session = s;
        final boolean bringFront = LemurXDeviceConfig.foreground() && foreground();
        TabInfo tab =
                onUi(() -> {
                    Tab existing = findTab(session.tab);
                    // 控制台标签留在原地。这个会话自己的网页标签可以接着打开新网址。
                    if (existing != null && isConsole(existing)) existing = null;
                    if (existing != null) {
                        existing.loadUrl(new LoadUrlParams(url));
                        if (bringFront) selectTab(existing);
                        return TabInfo.of(existing);
                    }
                    Tab opened = openTab(url, !bringFront);
                    if (opened != null && bringFront) selectTab(opened);
                    return opened == null ? null : TabInfo.of(opened);
                });
        if (tab == null) {
            throw new SessionError(500, "OPEN_FAILED", "Aero could not open a tab.");
        }
        session.tab = tab.id;
        session.url = TextUtils.isEmpty(tab.url) ? url : tab.url;
        session.title = tab.title;
        session.status = "active";
        session.updatedAt = nowIso();
        sessions.put(id, session);
        saveSessions();
        showBanner(tab.id, ContextUtils.getApplicationContext().getString(
                R.string.lemurx_agent_opening));
        waitUntilSettled(tab.id);
        showBanner(tab.id, ContextUtils.getApplicationContext().getString(
                R.string.lemurx_agent_working));
        TabInfo after = onUi(() -> {
            Tab t = findTab(tab.id);
            return t == null ? null : TabInfo.of(t);
        });
        if (after != null) {
            session.url = TextUtils.isEmpty(after.url) ? session.url : after.url;
            session.title = after.title;
            session.updatedAt = nowIso();
            saveSessions();
            return session.view();
        }
        return session.view();
    }

    /** 取会话；标签被用户关掉就按原 URL 重开。 */
    private static Session getSession(String id) throws SessionError {
        Session s = sessions().get(id);
        if (s == null) {
            throw new SessionError(404, "SESSION_NOT_FOUND",
                    "Browser session not found on this device.");
        }
        final int tabId = s.tab;
        Boolean alive = onUi(() -> findTab(tabId) != null);
        if (alive == null || !alive) {
            if ("closed".equals(s.status) || !allowedUrl(s.url)) {
                throw new SessionError(409, "SESSION_CLOSED",
                        "The session is closed. Reopen it first.");
            }
            final String url = s.url;
            final boolean foreground = LemurXDeviceConfig.foreground();
            TabInfo reopened = onUi(() -> {
                Tab t = openTab(url, !foreground);
                return t == null ? null : TabInfo.of(t);
            });
            if (reopened == null) {
                throw new SessionError(500, "OPEN_FAILED", "Aero could not reopen the tab.");
            }
            s.tab = reopened.id;
            saveSessions();
        }
        return s;
    }

    private static JSONObject closeSession(String id) throws SessionError {
        Session s = sessions().get(id);
        if (s == null) {
            throw new SessionError(404, "SESSION_NOT_FOUND",
                    "Browser session not found on this device.");
        }
        final int tabId = s.tab;
        onUi(() -> {
            Tab tab = findTab(tabId);
            if (tab != null) closeTab(tab);
            if (sBannerTab == tabId) hideBanner();
            return Boolean.TRUE;
        });
        s.status = "closed";
        s.updatedAt = nowIso();
        saveSessions();
        return s.view();
    }

    /**
     * 当前前台的网页优先；控制台自己不算；没有前台网页时用这个会话上次的标签。把会话指到
     * 选中的标签上并（按设置）切到前台。
     */
    private static @Nullable TabInfo useContent(Session s) {
        final int preferred = s.tab;
        final boolean foreground = LemurXDeviceConfig.foreground();
        TabInfo tab = onUi(() -> {
            Tab chosen = contentTab(preferred);
            if (chosen == null) return null;
            if (foreground) selectTab(chosen);
            return TabInfo.of(chosen);
        });
        if (tab != null) {
            s.tab = tab.id;
            s.url = TextUtils.isEmpty(tab.url) ? s.url : tab.url;
            s.title = tab.title;
            saveSessions();
        }
        return tab;
    }

    // ---------------------------------------------------------------- 读页面（无障碍树）

    /** 拍一张当前标签的快照（后台线程）；拿不到返回 null。 */
    private static LemurXPage.@Nullable Snapshot snapshot(int tabId) {
        Object[] target = onUi(() -> {
            Tab t = findTab(tabId);
            if (t == null) return null;
            View v = t.getView();
            WebContents wc = t.getWebContents();
            return v == null || wc == null ? null : new Object[] {wc, v};
        });
        if (target == null) return null;
        return LemurXPage.capture((WebContents) target[0], (View) target[1], AX_MAX_NODES);
    }

    /** 当前有没有原生对话框挡在网页前（后台线程）。 */
    private static @Nullable JSONObject dialog(int tabId) {
        return onUi(() -> {
            ChromeTabbedActivity activity = sActivity;
            if (activity == null) return null;
            Tab t = findTab(tabId);
            return LemurXDialogs.describe(activity, t == null ? null : t.getWebContents());
        });
    }

    private static JSONObject readSession(String id) throws SessionError {
        Session s = getSession(id);
        TabInfo tab = useContent(s);
        if (tab == null) {
            throw new SessionError(502, "READ_FAILED",
                    "The page did not return any text (still loading, or not an http page).");
        }
        waitUntilSettled(tab.id);
        // 先看对话框：JS alert()/confirm()/prompt() 把渲染进程主线程卡住了，无障碍树快照只会等到
        // 超时，所以有模态对话框时不拍快照，正文留空、把对话框描述出来即可。
        JSONObject dialog = dialog(tab.id);
        boolean rendererBlocked = dialog != null && "modal".equals(dialog.optString("kind"));
        LemurXPage.Snapshot snap = rendererBlocked ? null : snapshot(tab.id);
        if (!rendererBlocked && (snap == null || TextUtils.isEmpty(snap.text))) {
            sleep(800);
            LemurXPage.Snapshot again = snapshot(tab.id);
            if (again != null) snap = again;
        }
        if (snap == null && dialog == null) {
            throw new SessionError(502, "READ_FAILED",
                    "The page did not return any text (still loading, or not an http page).");
        }
        if (snap != null) {
            if (!TextUtils.isEmpty(snap.url)) s.url = snap.url;
            if (!TextUtils.isEmpty(snap.title)) s.title = snap.title;
        }
        s.status = "active";
        s.updatedAt = nowIso();
        saveSessions();
        JSONObject out = new JSONObject();
        try {
            String title = snap == null ? s.title : snap.title;
            if (title == null) title = "";
            out.put("url", snap == null || TextUtils.isEmpty(snap.url) ? s.url : snap.url);
            out.put("title", title.length() > 300 ? title.substring(0, 300) : title);
            out.put("text", snap == null ? "" : snap.text);
            out.put("truncated", snap != null && snap.truncated);
            if (snap != null) {
                out.put("elementCount", snap.elements.size());
                LemurXPage.Element focused = snap.focused();
                if (focused != null) out.put("focus", LemurXPage.brief(focused));
                out.put("pageScale", Math.round(snap.pageScale * 100) / 100.0);
            }
            if (dialog != null) out.put("dialog", dialog);
        } catch (Exception ignored) {
            // 键是常量
        }
        // 阅读停顿：真人读完一屏字再动手，正文越长停得越久（下一条输入前生效）。
        int len = snap == null ? 0 : snap.text.length();
        sDwellUntil = SystemClock.uptimeMillis() + (long) clamp(len / 50.0, 400, 2_500);
        return out;
    }

    /** GET /sessions/:id/elements：可操作元素清单（1280x800 画布坐标）。 */
    private static JSONObject elementsSession(String id) throws SessionError {
        Session s = getSession(id);
        TabInfo tab = useContent(s);
        if (tab == null) {
            throw new SessionError(502, "READ_FAILED", "The page is not ready.");
        }
        LemurXPage.Snapshot snap = snapshot(tab.id);
        if (snap == null) {
            throw new SessionError(502, "READ_FAILED", "The page is not ready.");
        }
        JSONObject out = new JSONObject();
        try {
            out.put("url", snap.url);
            out.put("title", snap.title);
            out.put("elements", snap.elementsJson());
            out.put("focusId", snap.focusId);
            out.put("pageScale", Math.round(snap.pageScale * 100) / 100.0);
            JSONObject dialog = dialog(tab.id);
            if (dialog != null) out.put("dialog", dialog);
        } catch (Exception ignored) {
            // 键是常量
        }
        return out;
    }

    // ---------------------------------------------------------------- 截图

    private static String screenshotSession(String id) throws SessionError {
        Session s = getSession(id);
        TabInfo tab = useContent(s);
        if (tab == null) {
            throw new SessionError(502, "SCREENSHOT_FAILED", "Aero could not capture the tab.");
        }
        // JPEG：接管控制台每 2 秒拉一帧，PNG 在手机分辨率下 base64 后会有几 MB。
        // 合成器拷贝输出：只有网页视口本身，不含 agent 自己的手指 / 键盘 / 横幅；页面无感。
        String data = captureJpeg(tab.id, 70);
        if (data != null) return data;
        throw new SessionError(502, "SCREENSHOT_FAILED", "Aero could not capture the tab.");
    }

    /** 视口截图 → JPEG base64（后台线程等 UI 线程回调）；失败返回 null。 */
    private static @Nullable String captureJpeg(int tabId, int quality) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> out = new AtomicReference<>();
        ThreadUtils.postOnUiThread(() -> {
            Tab tab = findTab(tabId);
            WebContents wc = tab == null ? null : tab.getWebContents();
            if (wc == null) {
                latch.countDown();
                return;
            }
            try {
                LemurXAx.capture(wc, quality, data -> {
                    out.set(data);
                    latch.countDown();
                });
            } catch (Throwable t) {
                Log.w(TAG, "capture", t);
                latch.countDown();
            }
        });
        if (!await(latch, 4_000)) return null;
        String data = out.get();
        return TextUtils.isEmpty(data) ? null : data;
    }

    // ---------------------------------------------------------------- 输入

    private static JSONObject inputSession(String id, JSONObject body) throws SessionError {
        Session s = getSession(id);
        if (userActive()) {
            throw new SessionError(409, "USER_ACTIVE",
                    "The user is touching the screen; wait a moment and try again.");
        }
        TabInfo tab = useContent(s);
        if (tab == null) {
            throw new SessionError(502, "INPUT_FAILED", "The page is not ready for input.");
        }
        String kind = body.optString("type", "");
        boolean ok;
        JSONObject resolved = null;
        if ("dialog".equals(kind)) {
            // 原生对话框不吃触摸坐标：直接走它自己的控制器。
            answerDialog(tab.id, body);
            ok = true;
        } else if ("click".equals(kind) && body.has("target")) {
            pace();
            LemurXPage.Element e = clickTarget(tab.id, body.opt("target"), body.optBoolean("long", false));
            resolved = LemurXPage.brief(e);
            ok = true;
        } else if ("click".equals(kind) && body.has("x") && body.has("y")) {
            pace();
            float[] vp = viewportPx(tab.id);
            if (vp == null) {
                throw new SessionError(502, "INPUT_FAILED", "The page is not ready for input.");
            }
            float x = (float) (clamp(body.optDouble("x", 0), 0, CONSOLE_W - 1) / CONSOLE_W * vp[0]);
            float y = (float) (clamp(body.optDouble("y", 0), 0, CONSOLE_H - 1) / CONSOLE_H * vp[1]);
            ok = gestureTap(tab.id, x, vp[2] + y, body.optBoolean("long", false));
        } else if ("text".equals(kind) && body.has("text")) {
            String text = body.optString("text", "");
            if (text.length() > 10_000) {
                throw new SessionError(400, "INVALID_INPUT", "Unsupported browser input.");
            }
            pace();
            if (body.has("target")) {
                // 先把焦点点进目标输入框（已经聚焦就不再点）。
                LemurXPage.Element e = focusTarget(tab.id, body.opt("target"));
                resolved = LemurXPage.brief(e);
            }
            int r = typeText(tab.id, text);
            if (r == TYPE_NO_FOCUS) {
                throw new SessionError(409, "NO_INPUT_FOCUS",
                        "No text field is focused. Click the field first, then type.");
            }
            ok = r == TYPE_OK;
        } else if ("key".equals(kind) && body.has("key")) {
            String name = body.optString("key", "");
            if (!LemurXHumanInput.isKnownKey(name)) {
                throw new SessionError(400, "INVALID_INPUT", "Unsupported key.");
            }
            pace();
            ok = pressKey(tab.id, name);
        } else if ("scroll".equals(kind) && body.has("deltaY")
                && Math.abs(body.optDouble("deltaY", 0)) <= 5000) {
            pace();
            float[] vp = viewportPx(tab.id);
            if (vp == null) {
                throw new SessionError(502, "INPUT_FAILED", "The page is not ready for input.");
            }
            // deltaY 是 CSS 像素；换成物理像素再限制在视口 55% 以内（一次手指能划的距离）。
            double dy = Math.floor(body.optDouble("deltaY", 0)) * vp[3];
            double span = Math.max(80 * vp[3], Math.min(vp[1] * 0.55, Math.abs(dy)));
            ok = humanScroll(tab.id, vp, dy > 0 ? -span : span);
        } else if ("pinch".equals(kind) && body.has("scale")) {
            double scale = body.optDouble("scale", 1);
            if (!(scale >= 0.2 && scale <= 5) || Math.abs(scale - 1) < 0.05) {
                throw new SessionError(400, "INVALID_INPUT", "scale must be in 0.2..5.");
            }
            pace();
            float[] vp = viewportPx(tab.id);
            if (vp == null) {
                throw new SessionError(502, "INPUT_FAILED", "The page is not ready for input.");
            }
            float x = body.has("x")
                    ? (float) (clamp(body.optDouble("x", 0), 0, CONSOLE_W - 1) / CONSOLE_W * vp[0])
                    : vp[0] / 2;
            float y = body.has("y")
                    ? (float) (clamp(body.optDouble("y", 0), 0, CONSOLE_H - 1) / CONSOLE_H * vp[1])
                    : vp[1] / 2;
            ok = pinchZoom(tab.id, vp, x, vp[2] + y, scale);
        } else {
            throw new SessionError(400, "INVALID_INPUT", "Unsupported browser input.");
        }
        if (!ok) {
            if (userActive()) {
                throw new SessionError(409, "USER_ACTIVE",
                        "The user took over the screen; the input was cancelled.");
            }
            throw new SessionError(502, "INPUT_FAILED", "Aero could not deliver the input.");
        }
        sLastInputMs = SystemClock.uptimeMillis();
        final int tabId = tab.id;
        TabInfo after = onUi(() -> {
            Tab t = findTab(tabId);
            return t == null ? null : TabInfo.of(t);
        });
        if (after != null) {
            s.url = TextUtils.isEmpty(after.url) ? s.url : after.url;
            s.title = after.title;
        }
        s.status = "active";
        s.updatedAt = nowIso();
        saveSessions();
        showBanner(tab.id, ContextUtils.getApplicationContext().getString(
                R.string.lemurx_agent_working));
        JSONObject view = s.view();
        try {
            if (resolved != null) view.put("target", resolved);
            JSONObject dialog = dialog(tab.id);
            if (dialog != null) view.put("dialog", dialog);
        } catch (Exception ignored) {
            // 键是常量
        }
        return view;
    }

    /** input {type:"dialog", ...}：回答当前的原生对话框。 */
    private static void answerDialog(int tabId, JSONObject body) throws SessionError {
        String err = onUi(() -> {
            ChromeTabbedActivity activity = sActivity;
            if (activity == null) return "Aero is not on screen.";
            Tab t = findTab(tabId);
            String r = LemurXDialogs.answer(activity, t == null ? null : t.getWebContents(), body);
            return r == null ? "" : r;
        });
        if (err == null) {
            throw new SessionError(502, "INPUT_FAILED", "Aero could not reach the dialog.");
        }
        if (!err.isEmpty()) {
            boolean none = err.startsWith("No dialog");
            throw new SessionError(none ? 409 : 400, none ? "NO_DIALOG" : "INVALID_INPUT", err);
        }
        caption("对话框  " + body.optString("action", body.has("select") ? "选择" : "确定"));
        sleep(250 + (int) (Math.random() * 250));
    }

    // ---------------------------------------------------------------- 语义定位（落指时重算坐标）

    private static final int TARGET_TRIES = 3;
    private static final int TARGET_TRIES_MAX = 8;

    /**
     * 按 target 定位并点击：拍快照 → 匹配 → 不在视口就滚过去再拍（最多 3 次）→ 太小就双指放大
     * 再拍 → 以此刻的矩形中心落指。失败抛出带说明的 SessionError。
     */
    private static LemurXPage.Element clickTarget(int tabId, Object target, boolean longPress)
            throws SessionError {
        Object[] located = locate(tabId, target);
        LemurXPage.Snapshot snap = (LemurXPage.Snapshot) located[0];
        LemurXPage.Element e = (LemurXPage.Element) located[1];
        // 点在矩形内靠中心的随机位置（真人不会每次都点正中）。
        float jx = (float) ((Math.random() - 0.5) * Math.min(e.px.width() * 0.3, 24 * snap.dsf));
        float jy = (float) ((Math.random() - 0.5) * Math.min(e.px.height() * 0.3, 12 * snap.dsf));
        float x = snap.tapX(e) + jx;
        float y = snap.tapY(e) + jy;
        caption((longPress ? "长按  " : "点击  ") + label(e));
        Log.i(TAG, "%s %s at %.0f,%.0f rect=%s", longPress ? "long-press" : "tap", label(e),
                x, y, e.px.toShortString());
        if (!gestureTap(tabId, x, y, longPress)) {
            if (userActive()) {
                throw new SessionError(409, "USER_ACTIVE",
                        "The user took over the screen; the input was cancelled.");
            }
            throw new SessionError(502, "INPUT_FAILED", "Aero could not deliver the input.");
        }
        return e;
    }

    /** text 的 target：目标已聚焦就不动；否则点它并等焦点进去。 */
    private static LemurXPage.Element focusTarget(int tabId, Object target) throws SessionError {
        LemurXPage.Snapshot snap = snapshot(tabId);
        if (snap != null) {
            LemurXPage.Element e = LemurXPage.resolve(snap, target);
            if (e != null && e.focused) return e;
        }
        LemurXPage.Element e = clickTarget(tabId, target, false);
        // 等页面把焦点移进去（最多 1.2 s）。
        long deadline = SystemClock.uptimeMillis() + 1_200;
        while (SystemClock.uptimeMillis() < deadline) {
            WebContents wc = onUi(() -> {
                Tab t = findTab(tabId);
                return t == null ? null : t.getWebContents();
            });
            if (wc != null && LemurXHumanInput.hasEditableFocus(wc)) break;
            sleep(80);
        }
        return e;
    }

    /** 定位并保证可见、够大：返回 {快照, 元素}。 */
    private static Object[] locate(int tabId, Object target) throws SessionError {
        LemurXPage.Snapshot snap = snapshot(tabId);
        if (snap == null) {
            throw new SessionError(502, "INPUT_FAILED", "The page is not ready for input.");
        }
        LemurXPage.Element e = LemurXPage.resolve(snap, target);
        if (e == null) {
            throw new SessionError(404, "TARGET_NOT_FOUND",
                    "No element matches the target. Call GET /sessions/:id/elements and use an id or the visible text.");
        }
        if (e.disabled) {
            throw new SessionError(409, "TARGET_DISABLED", "That element is disabled.");
        }
        Object[] located = scrollIntoView(tabId, snap, e, target);
        snap = (LemurXPage.Snapshot) located[0];
        e = (LemurXPage.Element) located[1];
        if (!snap.visible(e)) {
            throw new SessionError(409, "TARGET_OFFSCREEN",
                    "The element could not be scrolled into view.");
        }
        // 太小就放大再点（真人也会这么做）；已经放大过就不再放。阈值 18dp：普通一行链接
        // （20dp 上下）不该触发，9px 字号的小按钮（16dp 左右）才需要。
        float minDim = Math.min(e.px.width(), e.px.height());
        if (minDim < 18 * snap.dsf && snap.pageScale < snap.minPageScale * 2.2f) {
            double scale = clamp((36 * snap.dsf) / Math.max(1, minDim), 1.6, 3.0);
            float[] vp = {snap.viewportW, snap.viewportH, snap.contentOffsetY, snap.dsf};
            Log.i(TAG, "zoom-in %s rect=%s minDim=%.0f scale=%.2f pageScale=%.2f", label(e),
                    e.px.toShortString(), minDim, scale, snap.pageScale);
            caption("放大  " + label(e));
            if (pinchZoom(tabId, vp, e.cx(), e.cy(), scale)) {
                sleep(450 + (int) (Math.random() * 200));
                LemurXPage.Snapshot again = snapshot(tabId);
                LemurXPage.Element e2 = again == null ? null : again.byId(e.id);
                if (e2 == null && again != null) e2 = LemurXPage.resolve(again, target);
                if (e2 != null) {
                    // 放大后元素常被推出视口（两指中心不可能正好压在它上面），再滚回来。
                    located = scrollIntoView(tabId, again, e2, target);
                    again = (LemurXPage.Snapshot) located[0];
                    e2 = (LemurXPage.Element) located[1];
                    if (again.visible(e2)) {
                        snap = again;
                        e = e2;
                    } else {
                        throw new SessionError(409, "TARGET_OFFSCREEN",
                                "The element left the viewport after zooming in.");
                    }
                }
            }
        }
        return new Object[] {snap, e};
    }

    /**
     * 把元素滚进视口（先竖后横），{@link #TARGET_TRIES}～{@link #TARGET_TRIES_MAX} 次；每次滚完重新快照、按 id（其次按
     * target）找回元素。返回 {snapshot, element}，调用方自己判断最终可见与否。
     */
    private static Object[] scrollIntoView(int tabId, LemurXPage.Snapshot snap, LemurXPage.Element e,
            Object target) throws SessionError {
        // 次数按距离定：每次最多滚 55% 视口，远的多滚几次，但总有上限（每次约 1.5 s）。
        int tries = (int) clamp(Math.ceil(Math.abs(snap.scrollNeededY(e)) / (snap.viewportH * 0.55)) + 2,
                TARGET_TRIES, TARGET_TRIES_MAX);
        for (int i = 0; i < tries; i++) {
            float needY = snap.scrollNeededY(e);
            float needX = needY != 0 ? 0 : snap.scrollNeededX(e);
            if (needY == 0 && needX == 0 && snap.visible(e)) break;
            if (needY == 0 && needX == 0 && e.offscreen) {
                needY = snap.viewportH * 0.4f; // 在视口范围内却被标记离屏：往下探一屏
            }
            if (needY == 0 && needX == 0) break;
            float[] vp = {snap.viewportW, snap.viewportH, snap.contentOffsetY, snap.dsf};
            Log.i(TAG, "scroll-into-view #%d %s rect=%s needY=%.0f needX=%.0f vp=%dx%d+%d scale=%.2f pan=%d,%d",
                    i, label(e), e.px.toShortString(), needY, needX, (int) snap.viewportW,
                    (int) snap.viewportH, (int) snap.contentOffsetY, snap.pageScale,
                    snap.scrollXPix, snap.scrollYPix);
            caption("滚动到  " + label(e));
            boolean ok;
            if (needY != 0) {
                float span = (float) Math.max(60 * snap.dsf, Math.min(snap.viewportH * 0.55, Math.abs(needY)));
                ok = humanScroll(tabId, vp, needY > 0 ? -span : span);
            } else {
                float span = (float) Math.max(40 * snap.dsf, Math.min(snap.viewportW * 0.5, Math.abs(needX)));
                ok = humanScrollX(tabId, vp, needX > 0 ? -span : span);
            }
            if (!ok) {
                if (userActive()) {
                    throw new SessionError(409, "USER_ACTIVE",
                            "The user took over the screen; the input was cancelled.");
                }
                break;
            }
            sleep(350 + (int) (Math.random() * 250));
            LemurXPage.Snapshot again = snapshot(tabId);
            if (again == null) break;
            LemurXPage.Element e2 = again.byId(e.id);
            if (e2 == null) e2 = LemurXPage.resolve(again, target);
            if (e2 == null) break;
            // 滚了却没动（到底了 / 这个方向滚不了）：别再试。
            boolean moved = Math.abs(e2.cx() - e.cx()) > 4 * snap.dsf
                    || Math.abs(e2.cy() - e.cy()) > 4 * snap.dsf;
            snap = again;
            e = e2;
            if (!moved) break;
        }
        return new Object[] {snap, e};
    }

    private static String label(LemurXPage.Element e) {
        String s = !e.name.isEmpty() ? e.name : !e.value.isEmpty() ? e.value
                : !e.placeholder.isEmpty() ? e.placeholder : e.role;
        return s.length() > 28 ? s.substring(0, 28) + "…" : s;
    }

    // ---------------------------------------------------------------- 节奏（真人不会每 50 ms 一个动作）

    private static volatile long sLastInputMs;
    private static volatile long sDwellUntil;

    /**
     * 两条输入之间至少隔 300–900 ms；刚 read 过就等阅读停顿结束。后台线程睡眠，不占 UI。
     */
    private static void pace() {
        long now = SystemClock.uptimeMillis();
        long wait = 0;
        if (sLastInputMs > 0) {
            long gap = 300 + (long) (Math.random() * 600);
            wait = Math.max(wait, sLastInputMs + gap - now);
        }
        wait = Math.max(wait, sDwellUntil - now);
        if (wait > 0) sleep(Math.min(wait, 3_000));
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 真人式滚动：{@code dyPx} 为手指位移（负 = 内容上移 / 往下看）。四分之一的概率滑过头 8–15%，
     * 停一拍再往回拨一点。{@code vp} = {宽, 高, 顶部偏移, dsf}。
     */
    private static boolean humanScroll(int tabId, float[] vp, double dyPx) {
        float x = vp[0] / 2 + (float) ((Math.random() - 0.5) * vp[0] * 0.2);
        float y1 = vp[2] + vp[1] * (dyPx < 0 ? 0.62f : 0.38f);
        boolean overshoot = Math.random() < 0.25 && Math.abs(dyPx) > 120 * vp[3];
        double factor = overshoot ? 1.08 + Math.random() * 0.07 : 1;
        float y2 = (float) (y1 + dyPx * factor);
        if (!gestureSwipe(tabId, x, y1, x, y2)) return false;
        if (overshoot) {
            sleep(250 + (int) (Math.random() * 250));
            double back = -dyPx * (factor - 1);
            float bx = x + (float) ((Math.random() - 0.5) * 30 * vp[3]);
            float by1 = vp[2] + vp[1] * 0.5f;
            if (!gestureSwipe(tabId, bx, by1, bx, (float) (by1 + back))) return false;
        }
        return true;
    }

    /**
     * 横向滚动（物理像素；dxPx < 0 表示内容往左走、看右边）。起落点都离屏幕左右边缘 18% 以上，
     * 免得被系统当成返回手势。
     */
    private static boolean humanScrollX(int tabId, float[] vp, double dxPx) {
        float y = vp[2] + vp[1] * (0.45f + (float) ((Math.random() - 0.5) * 0.2));
        float margin = vp[0] * 0.18f;
        float maxSpan = vp[0] - 2 * margin;
        double dx = clamp(dxPx, -maxSpan, maxSpan);
        float x1 = dx < 0 ? vp[0] - margin : margin;
        float x2 = (float) (x1 + dx);
        return gestureSwipe(tabId, x1, y, x2, y);
    }
    // ---------------------------------------------------------------- 页面读取 / 输入（后台线程，内部切 UI）

    /**
     * 视口（物理像素）{宽, 高, 网页在视图里的顶部偏移, 设备缩放}。来自 RenderCoordinates。
     */
    private static float @Nullable [] viewportPx(int tabId) {
        return onUi(() -> {
            Tab tab = findTab(tabId);
            View view = tab == null ? null : tab.getView();
            WebContents wc = tab == null ? null : tab.getWebContents();
            if (view == null || wc == null) return null;
            float density = view.getResources().getDisplayMetrics().density;
            int w = view.getWidth();
            int h = view.getHeight();
            int top = 0;
            try {
                RenderCoordinates rc = RenderCoordinates.fromWebContents(wc);
                if (rc.getLastFrameViewportWidthPixInt() > 0) {
                    w = rc.getLastFrameViewportWidthPixInt();
                }
                if (rc.getLastFrameViewportHeightPixInt() > 0) {
                    h = rc.getLastFrameViewportHeightPixInt();
                }
                top = rc.getContentOffsetYPixInt();
            } catch (Throwable t) {
                Log.i(TAG, "render coords: %s", t.toString());
            }
            if (w <= 0 || h <= 0) return null;
            return new float[] {w, h, top, density};
        });
    }

    private static final int TYPE_OK = 0;
    private static final int TYPE_NO_FOCUS = 1;
    private static final int TYPE_FAILED = 2;

    /** 输入法通道打字（后台线程按节拍睡眠）。 */
    private static int typeText(int tabId, String text) {
        WebContents wc = onUi(() -> {
            Tab t = findTab(tabId);
            return t == null ? null : t.getWebContents();
        });
        if (wc == null) return TYPE_FAILED;
        if (!LemurXHumanInput.hasEditableFocus(wc)) return TYPE_NO_FOCUS;
        LemurXAgentKeyboard keys = onUi(() -> showKeyboard(8_000));
        awaitImeHidden();
        boolean ok = LemurXHumanInput.typeText(wc, text, LemurXDeviceBridge::userActive, keys);
        if (keys != null) ThreadUtils.postOnUiThread(() -> scheduleHideKeyboard(2_500));
        return ok ? TYPE_OK : TYPE_FAILED;
    }

    private static boolean pressKey(int tabId, String name) {
        Object[] target = onUi(() -> {
            Tab t = findTab(tabId);
            if (t == null) return null;
            return new Object[] {t.getWebContents(), t.getContentView()};
        });
        if (target == null) return false;
        WebContents wc = (WebContents) target[0];
        if (LemurXHumanInput.hasEditableFocus(wc) && LemurXHumanInput.isKnownKey(name)) {
            // 有输入焦点：手指在 agent 键盘上按出这个键。
            LemurXAgentKeyboard keys = onUi(() -> showKeyboard(2_500));
            if (keys != null) keys.pressKey(name);
        } else {
            caption("按键  " + name);
        }
        return LemurXHumanInput.pressKey(wc, (View) target[1], name);
    }

    // ---------------------------------------------------------------- Agent 键盘

    /**
     * 显示 agent 自己的键盘并收起系统输入法（UI 线程）；{@code idleMs} 内没再用就自动收起。
     * 系统输入法是别的进程的窗口，手指画不到它上面，所以 agent 打字时用自己的那块。
     */
    private static @Nullable LemurXAgentKeyboard showKeyboard(long idleMs) {
        ThreadUtils.assertOnUiThread();
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return null;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return null;
        if (sKeyboard == null || sKeyboard.getParent() == null) {
            // 只避让系统导航条（不能用 systemWindowInsets——此刻系统输入法可能还在，它的高度
            // 也算在里面）；LemurX 底栏露着时再把键盘抬到它上面。
            int inset = 0;
            WindowInsets wi = activity.getWindow().getDecorView().getRootWindowInsets();
            if (wi != null) {
                inset = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? wi.getInsets(WindowInsets.Type.navigationBars()).bottom
                        : wi.getStableInsetBottom();
            }
            inset = Math.max(inset, LemurXConsoleShell.bottomBarHeight());
            sKeyboard = new LemurXAgentKeyboard(activity, inset);
            content.addView(sKeyboard, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM));
        }
        LemurXTouchOverlay o = overlay();
        if (o != null) o.bringToFront();
        InputMethodManager imm =
                (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(content.getWindowToken(), 0);
        scheduleHideKeyboard(idleMs);
        return sKeyboard;
    }

    /**
     * 等系统输入法真正收起再开始打字（最多 800 ms，再留 150 ms 让它收尾）。Gboard 在收起时会
     * 对 InputConnection 调 finishComposingText；如果这时我们的第一个字正在组合中，就会被它
     * 提前提交，然后下一次 setComposingText 又插入一遍——首字母重复。桥接线程调用。
     */
    private static void awaitImeHidden() {
        long deadline = SystemClock.uptimeMillis() + 800;
        while (SystemClock.uptimeMillis() < deadline) {
            Boolean visible = onUi(LemurXDeviceBridge::imeVisible);
            if (visible == null || !visible) break;
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 系统输入法此刻是否在屏幕上（UI 线程）。 */
    private static boolean imeVisible() {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        WindowInsets wi = activity.getWindow().getDecorView().getRootWindowInsets();
        return wi != null && wi.isVisible(WindowInsets.Type.ime());
    }

    private static void scheduleHideKeyboard(long idleMs) {
        ThreadUtils.assertOnUiThread();
        View k = sKeyboard;
        if (k == null) return;
        k.removeCallbacks(sHideKeyboard);
        k.postDelayed(sHideKeyboard, idleMs);
    }

    /** 收起 agent 键盘（任意线程）。不重新唤起系统输入法——用户再点输入框它自然会出来。 */
    static void hideKeyboard() {
        ThreadUtils.runOnUiThread(LemurXDeviceBridge::hideKeyboardNow);
    }

    private static void hideKeyboardNow() {
        View k = sKeyboard;
        sKeyboard = null;
        if (k == null) return;
        k.removeCallbacks(sHideKeyboard);
        if (k.getParent() instanceof ViewGroup) ((ViewGroup) k.getParent()).removeView(k);
    }

    /** 已经挂上的手指可视化层；没有就返回 null（任意线程读）。 */
    static @Nullable LemurXTouchOverlay overlayIfAny() {
        return sOverlay;
    }

    /** 底部字幕（任意线程）。 */
    private static void caption(String text) {
        ThreadUtils.runOnUiThread(() -> {
            LemurXTouchOverlay o = overlay();
            if (o != null) o.caption(text);
        });
    }

    /** 真人点击（物理像素，相对标签视图）。后台线程等待手势播完。 */
    private static boolean gestureTap(int tabId, float x, float y, boolean longPress) {
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] ok = new boolean[1];
        ThreadUtils.postOnUiThread(() -> {
            Tab tab = findTab(tabId);
            View view = tab == null ? null : tab.getView();
            if (view == null) {
                latch.countDown();
                return;
            }
            LemurXHumanInput.Callback done = result -> {
                ok[0] = result;
                latch.countDown();
                // 点到输入框：系统输入法会弹出来；换成 agent 自己的键盘，让用户看见接下来的敲击。
                // 页面可能在点击后过一会儿才把焦点移进输入框（搜索浮层的动画等），所以查两次。
                WebContents wc = tab.getWebContents();
                for (long delay : new long[] {350, 1_000}) {
                    ThreadUtils.postOnUiThreadDelayed(() -> {
                        if (userActive()) return;
                        if (LemurXHumanInput.hasEditableFocus(wc)) {
                            showKeyboard(8_000);
                        } else if (delay == 350) {
                            hideKeyboardNow();
                        }
                    }, delay);
                }
            };
            if (longPress) {
                LemurXHumanInput.longPress(view, x, y, 650, overlay(), LemurXDeviceBridge::userActive, done);
            } else {
                LemurXHumanInput.tap(view, x, y, overlay(), LemurXDeviceBridge::userActive, done);
            }
        });
        return await(latch, 3_000) && ok[0];
    }

    private static boolean gestureSwipe(int tabId, float x1, float y1, float x2, float y2) {
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] ok = new boolean[1];
        ThreadUtils.postOnUiThread(() -> {
            Tab tab = findTab(tabId);
            View view = tab == null ? null : tab.getView();
            if (view == null) {
                latch.countDown();
                return;
            }
            LemurXHumanInput.swipe(view, x1, y1, x2, y2, /* settle= */ true, overlay(),
                    LemurXDeviceBridge::userActive, result -> {
                        ok[0] = result;
                        latch.countDown();
                    });
        });
        return await(latch, 4_000) && ok[0];
    }

    /** 当前页面缩放（UI 线程读 RenderCoordinates）；拿不到返回 0。 */
    private static float pageScale(int tabId) {
        Float f = onUi(() -> {
            Tab t = findTab(tabId);
            WebContents wc = t == null ? null : t.getWebContents();
            return wc == null ? 0f : RenderCoordinates.fromWebContents(wc).getPageScaleFactor();
        });
        return f == null ? 0 : f;
    }

    /**
     * 双指缩放并确认生效：导航后的第一个双指手势常被整个吞掉（页面缩放纹丝不动，再来一次就好，
     * 原因在 Chromium 合成器一侧，真人遇到也只会再捏一次），所以缩放前后比对 pageScale，
     * 没变就隔一拍重做一次。已经到缩放上下限（放不大 / 缩不小）的也在这里顺带识别，不白重试。
     */
    private static boolean pinchZoom(int tabId, float[] vp, float cx, float cy, double scale) {
        float before = pageScale(tabId);
        String limits = onUi(() -> {
            Tab t = findTab(tabId);
            WebContents wc = t == null ? null : t.getWebContents();
            if (wc == null) return "?";
            org.chromium.content.browser.RenderCoordinatesImpl rc =
                    org.chromium.content.browser.RenderCoordinatesImpl.fromWebContents(wc);
            return rc.getMinPageScaleFactor() + ".." + rc.getMaxPageScaleFactor();
        });
        Log.i(TAG, "pinch-zoom x%.2f: pageScale=%.2f limits=%s", scale, before, limits);
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!gesturePinch(tabId, vp, cx, cy, scale)) return false;
            sleep(300 + (int) (Math.random() * 200));
            float after = pageScale(tabId);
            if (before <= 0 || after <= 0 || Math.abs(after - before) / before > 0.03) return true;
            Log.i(TAG, "pinch had no effect (scale %.2f -> %.2f), retrying", before, after);
            if (userActive()) return false;
        }
        return true;
    }

    /**
     * 真人双指缩放（物理像素）。{@code scale} > 1 放大。两指张合的跨度按视口算：放大时从小张到大，
     * 缩小时反过来；跨度上限是视口短边的 70%。
     */
    private static boolean gesturePinch(int tabId, float[] vp, float cx, float cy, double scale) {
        float shortSide = Math.min(vp[0], vp[1]);
        float maxSpan = shortSide * 0.7f;
        float startSpan;
        float endSpan;
        if (scale > 1) {
            startSpan = (float) Math.max(40 * vp[3], Math.min(shortSide * 0.25, maxSpan / scale));
            endSpan = (float) (startSpan * scale);
        } else {
            startSpan = (float) Math.min(maxSpan, shortSide * 0.6);
            endSpan = (float) Math.max(40 * vp[3], startSpan * scale);
        }
        // 中心离边太近两指会出屏：往里挪。两指连线接近竖直（见 LemurXHumanInput.pinch），
        // 横向只占跨度的一半左右，所以横向少挪一点，缩放中心尽量留在目标上。
        float half = Math.max(startSpan, endSpan) / 2 + 20 * vp[3];
        float halfX = Math.max(startSpan, endSpan) / 2 * 0.5f + 20 * vp[3];
        final float fx = (float) clamp(cx, halfX, vp[0] - halfX);
        final float fy = (float) clamp(cy, vp[2] + half, vp[2] + vp[1] - half);
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] ok = new boolean[1];
        ThreadUtils.postOnUiThread(() -> {
            Tab tab = findTab(tabId);
            View view = tab == null ? null : tab.getView();
            if (view == null) {
                latch.countDown();
                return;
            }
            LemurXHumanInput.pinch(view, fx, fy, startSpan, endSpan, overlay(),
                    LemurXDeviceBridge::userActive, result -> {
                        ok[0] = result;
                        latch.countDown();
                    });
        });
        return await(latch, 4_000) && ok[0];
    }

    private static boolean await(CountDownLatch latch, long ms) {
        try {
            return latch.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ---------------------------------------------------------------- 用户优先 / 前台

    /** 用户最后一次触屏的时间；这之后 2.5 s 内 agent 不动手。 */
    private static volatile long sUserTouchUntil;
    private static volatile boolean sUserFingerDown;

    /**
     * ChromeTabbedActivity.dispatchTouchEvent 调入（UI 线程）：所有经窗口进来的触摸都是用户的
     * （agent 的手指直接投给标签视图，不经过窗口）。
     * Agent 锁着界面时，触摸到不了页面，也不算接管；只有点胶囊上的「接管」才 pauseByUser。
     * 没锁时用户一碰屏幕，进行中的手势立刻取消。
     */
    public static void onUserTouch(MotionEvent event) {
        if (isLocked()) return;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) hideKeyboardNow();
        sUserFingerDown = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL;
        sUserTouchUntil = SystemClock.uptimeMillis() + 2_500;
    }

    /** Agent 正在操作：全屏锁着，只有「接管」可点。 */
    public static boolean isLocked() {
        View lock = sBanner;
        return lock != null && lock.getVisibility() == View.VISIBLE;
    }

    /** 底栏 / 覆盖层把自己抬到最前之后，把锁和手指层再抬回去。 */
    public static void keepLockOnTop() {
        ThreadUtils.assertOnUiThread();
        View lock = sBanner;
        if (lock == null || lock.getVisibility() != View.VISIBLE) return;
        lock.bringToFront();
        if (sOverlay != null) sOverlay.bringToFront();
    }

    static boolean userActive() {
        return sUserFingerDown || SystemClock.uptimeMillis() < sUserTouchUntil;
    }

    /** 这个窗口是不是在前台（用户看得见）。 */
    static boolean foreground() {
        ChromeTabbedActivity activity = sActivity;
        return activity != null
                && ApplicationStatus.getStateForActivity(activity) == ActivityState.RESUMED;
    }

    // ---------------------------------------------------------------- 标签页（UI 线程）

    /** 标签页快照，跨线程传递用。 */
    private static final class TabInfo {
        int id;
        String url = "";
        String title = "";

        static TabInfo of(Tab tab) {
            TabInfo info = new TabInfo();
            info.id = tab.getId();
            try {
                info.url = tab.getUrl() == null ? "" : tab.getUrl().getSpec();
            } catch (Exception e) {
                info.url = "";
            }
            info.title = tab.getTitle() == null ? "" : tab.getTitle();
            return info;
        }
    }

    private static @Nullable TabModelSelector selector() {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null || !activity.areTabModelsInitialized()) return null;
        try {
            return activity.getTabModelSelector();
        } catch (Exception e) {
            return null;
        }
    }

    private static @Nullable Tab findTab(int tabId) {
        if (tabId == Tab.INVALID_TAB_ID) return null;
        TabModelSelector selector = selector();
        if (selector == null) return null;
        Tab tab = selector.getModel(false).getTabById(tabId);
        if (tab == null) tab = selector.getModel(true).getTabById(tabId);
        return tab;
    }

    private static boolean isConsole(Tab tab) {
        String url;
        try {
            url = tab.getUrl() == null ? "" : tab.getUrl().getSpec();
        } catch (Exception e) {
            return false;
        }
        return MuseUrls.isMuse(url) || url.startsWith("chrome-native://")
                || url.startsWith("chrome://");
    }

    private static boolean isWeb(Tab tab) {
        String url;
        try {
            url = tab.getUrl() == null ? "" : tab.getUrl().getSpec();
        } catch (Exception e) {
            return false;
        }
        return url.startsWith("http://") || url.startsWith("https://");
    }

    /** 当前前台的网页优先；控制台不算；再退到会话上次的标签；最后是最近活跃的网页标签。 */
    private static @Nullable Tab contentTab(int preferredId) {
        TabModelSelector selector = selector();
        if (selector == null) return null;
        Tab current = selector.getCurrentTab();
        if (current != null && !isConsole(current) && isWeb(current)) return current;
        Tab preferred = findTab(preferredId);
        if (preferred != null && !isConsole(preferred)) return preferred;
        Tab best = null;
        TabModel model = selector.getModel(false);
        for (int i = 0; i < model.getCount(); i++) {
            Tab t = model.getTabAt(i);
            if (t == null || isConsole(t) || !isWeb(t)) continue;
            if (best == null || t.getTimestampMillis() > best.getTimestampMillis()) best = t;
        }
        return best;
    }

    private static @Nullable Tab openTab(String url, boolean background) {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null || !activity.areTabModelsInitialized()) return null;
        @TabLaunchType
        int launchType =
                background ? TabLaunchType.FROM_LONGPRESS_BACKGROUND : TabLaunchType.FROM_CHROME_UI;
        return activity.getTabCreator(false).launchUrl(url, launchType);
    }

    private static void selectTab(Tab tab) {
        TabModelSelector selector = selector();
        if (selector == null) return;
        TabModelUtils.selectTabById(selector, tab.getId(), TabSelectionType.FROM_USER);
        LemurXConsoleShell.revealWeb();
    }

    /**
     * 后台线程：等到这个标签不再 loading，或超时。刚 loadUrl 时导航可能还没标成 loading，
     * 所以前几百毫秒即使 isLoading==false 也再看一眼。超时后让调用方继续读，不要卡死。
     */
    private static void waitUntilSettled(int tabId) {
        long start = SystemClock.uptimeMillis();
        long deadline = start + LOAD_WAIT_MS;
        boolean sawLoading = false;
        while (SystemClock.uptimeMillis() < deadline) {
            Boolean loading = onUi(() -> {
                Tab t = findTab(tabId);
                if (t == null) return null;
                return t.isLoading();
            });
            if (loading == null) return;
            if (loading) {
                sawLoading = true;
                sleep(LOAD_POLL_MS);
                continue;
            }
            if (!sawLoading && SystemClock.uptimeMillis() - start < 800) {
                sleep(LOAD_POLL_MS);
                continue;
            }
            sleep(LOAD_SETTLE_MS);
            Boolean again = onUi(() -> {
                Tab t = findTab(tabId);
                return t != null && t.isLoading();
            });
            if (again != null && !again) return;
        }
        Log.i(TAG, "tab %d still loading after %d ms", tabId, LOAD_WAIT_MS);
    }

    private static void closeTab(Tab tab) {
        TabModelSelector selector = selector();
        if (selector == null) return;
        selector.getModel(tab.isIncognito())
                .getTabRemover()
                .closeTabs(TabClosureParams.closeTab(tab).allowUndo(false).build(),
                        /* allowDialog= */ false);
    }

    // ---------------------------------------------------------------- 接管胶囊 / 通知（UI 线程）

    private static void showBanner(int tabId, String text) {
        if (!LemurXDeviceConfig.banner()) return;
        ThreadUtils.runOnUiThread(() -> {
            ChromeTabbedActivity activity = sActivity;
            if (activity == null) return;
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content == null) return;
            if (sBanner == null) {
                sBanner = buildLock(activity);
                content.addView(sBanner, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            TextView label = sBanner.findViewWithTag("label");
            if (label != null) label.setText(text);
            sBanner.setVisibility(View.VISIBLE);
            keepLockOnTop();
            sBannerTab = tabId;
        });
    }

    /** 手指可视化层（UI 线程）；按需挂到 android.R.id.content 最上层。 */
    private static @Nullable LemurXTouchOverlay overlay() {
        ThreadUtils.assertOnUiThread();
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return null;
        if (sOverlay != null && sOverlay.getParent() != null) {
            keepLockOnTop();
            return sOverlay;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return null;
        sOverlay = new LemurXTouchOverlay(activity);
        content.addView(sOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        keepLockOnTop();
        return sOverlay;
    }

    private static void hideBanner() {
        ThreadUtils.runOnUiThread(() -> {
            View banner = sBanner;
            sBanner = null;
            sBannerTab = Tab.INVALID_TAB_ID;
            if (banner != null && banner.getParent() instanceof ViewGroup) {
                ((ViewGroup) banner.getParent()).removeView(banner);
            }
            View overlay = sOverlay;
            sOverlay = null;
            if (overlay != null && overlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) overlay.getParent()).removeView(overlay);
            }
        });
    }

    /** 全屏透明锁：吃掉页面和底栏的触摸，中间只放一粒接管胶囊。 */
    private static View buildLock(Context context) {
        FrameLayout lock = new FrameLayout(context) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                return true;
            }
        };
        lock.setClickable(true);
        lock.setFocusable(false);
        View chip = buildBanner(context);
        lock.addView(chip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return lock;
    }

    private static View buildBanner(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // 半透明墨色玻璃：挡的面积只是中间一粒胶囊，网页还看得见。
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xA6132631);
        bg.setCornerRadius(dp(context, 22));
        bg.setStroke(Math.max(1, dp(context, 1)), 0x662EE6D6);
        row.setBackground(bg);
        row.setElevation(dp(context, 10));
        row.setPadding(dp(context, 14), dp(context, 8), dp(context, 8), dp(context, 8));
        View dot = new View(context);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(0xFF2EE6D6);
        dot.setBackground(dotBg);
        int dotSize = dp(context, 8);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
        dotLp.rightMargin = dp(context, 8);
        row.addView(dot, dotLp);
        TextView label = new TextView(context);
        label.setTag("label");
        label.setTextColor(0xF0FFFFFF);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(label,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button take = new Button(context);
        take.setText(R.string.lemurx_muse_take_over);
        take.setAllCaps(false);
        take.setTextColor(0xFF132631);
        take.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        take.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable takeBg = new GradientDrawable();
        takeBg.setColor(0xE6FFFFFF);
        takeBg.setCornerRadius(dp(context, 16));
        take.setBackground(takeBg);
        take.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        take.setMinHeight(dp(context, 32));
        take.setMinimumHeight(dp(context, 32));
        take.setMinWidth(0);
        take.setMinimumWidth(0);
        take.setOnClickListener(v -> pauseByUser());
        LinearLayout.LayoutParams takeLp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 32));
        takeLp.leftMargin = dp(context, 12);
        row.addView(take, takeLp);
        row.setOnClickListener(v -> pauseByUser());
        return row;
    }

    /** 接管：断开，本次进程内不再连接；重新打开 Aero 后恢复。 */
    private static void pauseByUser() {
        ThreadUtils.assertOnUiThread();
        sPausedByUser = true;
        stop();
        hideBanner();
        Toast.makeText(ContextUtils.getApplicationContext(), R.string.lemurx_agent_paused,
                Toast.LENGTH_LONG)
                .show();
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private static void notifyConnected(boolean on) {
        Context ctx = ContextUtils.getApplicationContext();
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (!on) {
            nm.cancel(NOTIFY_ID);
            return;
        }
        try {
            if (!nm.areNotificationsEnabled()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && nm.getNotificationChannel(NOTIFY_CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(NOTIFY_CHANNEL,
                        "Aero Agent", NotificationManager.IMPORTANCE_LOW));
            }
            Notification.Builder b =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? new Notification.Builder(ctx, NOTIFY_CHANNEL)
                            : new Notification.Builder(ctx);
            b.setContentTitle("Aero Agent 已连接")
                    .setContentText("Agent 可以在这台 Aero 里打开和阅读网页。")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true);
            Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (open != null) {
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                b.setContentIntent(PendingIntent.getActivity(ctx, NOTIFY_ID, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            }
            nm.notify(NOTIFY_ID, b.build());
        } catch (Exception e) {
            Log.i(TAG, "notify: %s", e.toString());
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 后台线程切到 UI 线程同步执行；超时或异常返回 null。禁止在 UI 线程上调用。 */
    private static <T> @Nullable T onUi(Callable<T> task) {
        if (ThreadUtils.runningOnUiThread()) {
            try {
                return task.call();
            } catch (Exception e) {
                Log.i(TAG, "ui task: %s", e.toString());
                return null;
            }
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        ThreadUtils.postOnUiThread(() -> {
            try {
                result.set(task.call());
            } catch (Exception e) {
                Log.i(TAG, "ui task: %s", e.toString());
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(UI_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return result.get();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String nowIso() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    /** 供设置页等处列出会话（数据，不是指令）。 */
    public static List<JSONObject> sessionViews() {
        List<JSONObject> out = new ArrayList<>();
        for (Session s : sessions().values()) out.add(s.view());
        return out;
    }
}
