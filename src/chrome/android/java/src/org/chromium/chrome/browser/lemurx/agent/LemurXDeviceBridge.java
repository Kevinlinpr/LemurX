// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.agent;

import android.app.Activity;
import android.app.Notification;
import android.app.PictureInPictureParams;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import org.chromium.chrome.browser.lemurx.muse.LemurXAccount;
import org.chromium.chrome.browser.lemurx.muse.MuseApi;
import org.chromium.chrome.browser.lemurx.muse.MusePage;
import org.chromium.chrome.browser.lemurx.muse.MuseUrls;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabCreationState;
import org.chromium.chrome.browser.tab.TabLaunchType;
import org.chromium.chrome.browser.tab.TabObserver;
import org.chromium.chrome.browser.tabmodel.TabModelObserver;
import org.chromium.chrome.browser.tab.TabSelectionType;
import org.chromium.chrome.browser.tabmodel.TabClosureParams;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.tabmodel.TabModelUtils;
import org.chromium.content_public.browser.ImeAdapter;
import org.chromium.content_public.browser.LoadUrlParams;
import org.chromium.content_public.browser.RenderCoordinates;
import org.chromium.content_public.browser.WebContents;
import org.chromium.url.GURL;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * 进行中的手势立即取消。窗口不在前台时一律回 DEVICE_BACKGROUND，这一轮挂起，
 * 等用户回到对话里点继续，再在同一个标签上接着操作。
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
 * <p>线程模型：一条后台线程守着到服务端的长连接；每条命令在它上面串行执行，需要碰标签页的步骤用
 * {@link #onUi} 切到 UI 线程并等结果（带超时），所以 UI 线程从不阻塞。这是从旧的 Lua 版本
 * （openmuse.lua）逐函数移植过来的，REST 语义保持一致。
 */
public final class LemurXDeviceBridge {
    private static final String TAG = "LemurXAgent";

    // 与 OpenMuse 侧对齐：BrowserService.request() 45s 超时，桥 40s，本机每条命令预算 30s。
    private static final long COMMAND_BUDGET_MS = 30_000;
    /** 长连接上 15 秒一个 ping；这么久没字节就当连接断了，重新接上。 */
    private static final int STREAM_READ_TIMEOUT_MS = 22_000;
    /** 看门狗：超过这个时间没收到字节，即使读线程还活着也拆掉重连（代理会把死连接撑着）。 */
    private static final long STALE_STREAM_MS = 25_000;
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
    /** 测试包被远程关停：本次进程内不再连接。点「接管」不会置位——长连接要留给下一轮对话。 */
    private static volatile boolean sPausedByUser;
    /** 切到后台之后为真：即使用户把窗口又打开了，也要等他在对话里点继续。画中画不算后台。 */
    private static volatile boolean sAgentSuspended;
    /** 正在进入或停留在 Aero 自己的画中画里。这期间页面操作继续，不挂起。 */
    private static volatile boolean sPipArmed;
    /** 画中画里藏起中间那粒「Agent 正在操作」，小窗只留网页。 */
    private static boolean sHideBannerChip;
    private static boolean sAgentPip;
    private static boolean sPipWanted;
    private static boolean sPipAuto;
    private static int sLeaveGen;
    private static ApplicationStatus.@Nullable ActivityStateListener sForegroundListener;
    /** 这个 Agent 正在操作的那一个标签。跳转都落在这里，页面自己弹出的标签会被折回来。 */
    private static int sAnchorTab = Tab.INVALID_TAB_ID;
    private static volatile boolean sHoldInTab;
    /** 页面上出现验证码：先停手，等用户在对话里把验证码发回来。 */
    private static volatile boolean sCaptchaHold;
    /** 当前这一页的地址。用户答过之后，同一地址不再打断。 */
    private static @Nullable String sCaptchaUrl;
    /** 用户已经在对话里答过的那一页。换了地址才重新检查。 */
    private static @Nullable String sCaptchaPassedUrl;
    private static boolean sFolding;
    private static @Nullable TabModelSelector sWatchedTabs;
    /** 正在向服务端登记这台手机，避免看门狗每 3 秒再发一次。 */
    private static volatile boolean sEnrolling;
    private static volatile long sNextEnrollMs;
    /**
     * 读长连接和执行命令必须分开。命令在读线程里跑的时候（打开网页、截图）没人读心跳，
     * 对端或代理会把这条连接拆掉；OkHttp 的读超时还会 interrupt 读线程，循环一看到中断标志就退出，
     * 这次进程里再也不会连上。
     */
    private static final ExecutorService sCommandRunner =
            Executors.newSingleThreadExecutor(
                    (Runnable r) -> {
                        Thread t = new Thread(r, "LemurXAgentCommands");
                        t.setDaemon(true);
                        return t;
                    });
    private static final Handler sWatchdog = new Handler(Looper.getMainLooper());
    private static final Runnable sWatchdogTick = LemurXDeviceBridge::watchdog;
    private static boolean sWatchdogArmed;

    private static @Nullable Map<String, Session> sSessions;
    private static @Nullable View sBanner;
    private static @Nullable LemurXTouchOverlay sOverlay;
    private static @Nullable LemurXAgentKeyboard sKeyboard;
    /** 系统输入法被按住不弹的那个网页。收起 agent 键盘时放开。 */
    private static @Nullable WebContents sSuppressedIme;
    /** 打字还没结束时不许收键盘、不许放开系统输入法。点击回调会迟到，不能把正在打的字打断。 */
    private static volatile int sImeHold;
    /**
     * 把页面暂时交给用户（验证码等）：锁缩小成胶囊，系统键盘可以出来。
     * 点「继续」或 Agent 下一次自己操作时收回。
     */
    private static volatile boolean sYielded;
    private static @Nullable View sImeView;
    private static final Runnable sImeWatch = LemurXDeviceBridge::watchIme;
    /** 最近一次打字核对的结果。只在这一次输入的回复里用，不留在日志里。 */
    private static boolean sFieldChecked;
    private static boolean sFieldMatched;
    private static @Nullable String sFieldValue;
    /** 本机刚按密码库填过的引用。下一次回执带上它，然后清掉。口令不放这里。 */
    private static @Nullable String sLoginRef;
    private static final Runnable sHideKeyboard = LemurXDeviceBridge::hideKeyboardNow;
    private static int sBannerTab = Tab.INVALID_TAB_ID;
    /** 这一轮在本机浏览器上的步骤。对话和网页上的半透明日志读的是同一份。 */
    private static final ArrayList<String> sActionLog = new ArrayList<>();
    /** 网页上的操作日志默认收起，只留一行，用户点开才展开。 */
    private static boolean sLogOpen;
    private static final ArrayList<Runnable> sActionListeners = new ArrayList<>();
    /** keepLockOnTop 抬锁时会再次触发层级监听，用它打断来回把底栏抬上来。 */
    private static boolean sLiftingLock;

    private LemurXDeviceBridge() {}

    // ---------------------------------------------------------------- 挂接

    /** ChromeTabbedActivity 原生初始化完成后（UI 线程），由 LemurXConsoleShell.attach 调入。 */
    public static void attach(ChromeTabbedActivity activity) {
        ThreadUtils.assertOnUiThread();
        if (activity.isDestroyed() || activity.isFinishing()) return;
        if (sActivity == activity && sThread != null && sThread.isAlive()) return;
        sActivity = activity;
        watchForeground(activity);
        overlay(); // 提前挂好并完成布局，第一下点击就能画在正确位置
        start();
        armWatchdog();
        noteAgentPresence();
    }

    /**
     * 离开前台就把正在进行的操作挂起。回到窗口不会自动接着做。
     * 画中画会先经过暂停，等一拍确认不是画中画再挂起。
     */
    private static void watchForeground(ChromeTabbedActivity activity) {
        if (sForegroundListener != null) {
            ApplicationStatus.unregisterActivityStateListener(sForegroundListener);
        }
        sForegroundListener =
                (watched, newState) -> {
                    if (watched != sActivity) return;
                    if (newState == ActivityState.RESUMED) {
                        sLeaveGen++;
                        return;
                    }
                    if (newState != ActivityState.PAUSED && newState != ActivityState.STOPPED) {
                        return;
                    }
                    if (inAgentPip(sActivity)) return;
                    int gen = ++sLeaveGen;
                    sWatchdog.postDelayed(
                            () -> {
                                if (gen != sLeaveGen) return;
                                ChromeTabbedActivity current = sActivity;
                                if (current == null || inAgentPip(current)) return;
                                int state = ApplicationStatus.getStateForActivity(current);
                                if (state == ActivityState.RESUMED) return;
                                MusePage.suspendIfRunning();
                            },
                            250);
                };
        ApplicationStatus.registerStateListenerForActivity(sForegroundListener, activity);
    }

    /**
     * 用户按 Home 离开时，这一轮还在操作就把窗口收成画中画，页面继续被操作。
     *
     * @return true 表示已经收进画中画，调用方不必再走视频画中画
     */
    public static boolean enterPipForAgent(Activity activity) {
        if (!(activity instanceof ChromeTabbedActivity) || activity != sActivity) return false;
        if (activity.isFinishing() || activity.isDestroyed()) return false;
        if (activity.isInPictureInPictureMode()) return sAgentPip;
        if (!agentBusy()) return false;
        sPipArmed = true;
        // 先露出 Agent 正在操作的那一页，再钉住整页尺寸缩进小窗。
        revealHeldPage();
        LemurXConsoleShell.setPipChrome(true);
        setBannerChipVisible(false);
        boolean entered = false;
        try {
            entered = activity.enterPictureInPictureMode(pipParams(false));
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.i(TAG, "pip: %s", e.getMessage());
        }
        if (!entered) {
            sPipArmed = false;
            LemurXConsoleShell.setPipChrome(false);
            setBannerChipVisible(true);
        }
        return entered;
    }

    /**
     * 画中画开关。Aero 自己收进去的那次不交给视频画中画处理。
     *
     * @return true 表示这次是 Aero 的画中画
     */
    public static boolean onAgentPipModeChanged(boolean inPicture) {
        if (inPicture) {
            if (!sPipArmed && !agentBusy()) return false;
            sAgentPip = true;
            sPipArmed = true;
            LemurXConsoleShell.setPipChrome(true);
            setBannerChipVisible(false);
            sLeaveGen++;
            return true;
        }
        if (!sAgentPip) return false;
        sAgentPip = false;
        sPipArmed = false;
        LemurXConsoleShell.setPipChrome(false);
        setBannerChipVisible(true);
        noteAgentPresence();
        return true;
    }

    /**
     * 正在干活时更新画中画比例。不交给系统自动进入：自动进入会跳过 Home 回调，
     * 来不及在窗口缩小前把网页钉在整页尺寸上。
     */
    public static void noteAgentPresence() {
        boolean busy = agentBusy();
        ChromeTabbedActivity activity = sActivity;
        if (activity == null || activity.isDestroyed()) return;
        boolean autoEnter = false;
        if (busy == sPipWanted && autoEnter == sPipAuto) return;
        sPipWanted = busy;
        sPipAuto = autoEnter;
        try {
            activity.setPictureInPictureParams(pipParams(autoEnter));
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.i(TAG, "pip params: %s", e.getMessage());
        }
    }

    private static boolean agentBusy() {
        return isHoldingTab() || MusePage.hasLiveWork();
    }

    private static boolean inAgentPip(@Nullable ChromeTabbedActivity activity) {
        return sPipArmed || (sAgentPip && activity != null && activity.isInPictureInPictureMode());
    }

    private static PictureInPictureParams pipParams(boolean autoEnter) {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
        Rational ratio = LemurXConsoleShell.pageAspectRatio();
        builder.setAspectRatio(ratio != null ? ratio : new Rational(9, 16));
        Rect src = LemurXConsoleShell.pageRectInWindow();
        if (src != null && src.width() > 0 && src.height() > 0) {
            builder.setSourceRectHint(src);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter);
            // 拖动小窗时先缩放现有画面，不要边拖边按新尺寸重排网页。
            builder.setSeamlessResizeEnabled(false);
        }
        return builder.build();
    }

    /** 画中画里看 Agent 正在操作的那一页，而不是停在对话上。 */
    private static void revealHeldPage() {
        if (!isHoldingTab()) return;
        Tab tab = findTab(anchorTabId());
        if (tab == null || !isWeb(tab)) return;
        selectTab(tab);
    }

    /** 对话确认这一轮要挂起。之后的页面命令一律拒绝，直到 {@link #markResumed}。 */
    public static void markSuspended() {
        sAgentSuspended = true;
        hideBanner();
        new Thread(() -> reportPresence(false), "LemurXPresence").start();
    }

    /** 用户点了继续：重新允许在这个前台标签上操作。 */
    public static void markResumed() {
        ThreadUtils.assertOnUiThread();
        sAgentSuspended = false;
        new Thread(() -> reportPresence(true), "LemurXPresence").start();
    }

    /** 窗口销毁：停轮询、收横幅、撤通知。UI 线程。 */
    public static void detach(ChromeTabbedActivity activity) {
        ThreadUtils.assertOnUiThread();
        if (sActivity != activity) return;
        if (sForegroundListener != null) {
            ApplicationStatus.unregisterActivityStateListener(sForegroundListener);
            sForegroundListener = null;
        }
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
            enrollThenStart();
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

    /** 登录 / 退出后换主人：旧流挂在上一份身份上，清掉令牌后按新会话重新登记。 */
    public static void reconnectIdentity() {
        if (sActivity == null || sPausedByUser || LemurXKillSwitch.isDisabled()) return;
        stop();
        sNextEnrollMs = 0;
        start();
    }

    /** 没有设备令牌时先登记。已登录但还没有 aero 票据时，先静默补一张。失败后退避，避免把服务端打满。 */
    private static void enrollThenStart() {
        if (sEnrolling || System.currentTimeMillis() < sNextEnrollMs) return;
        sEnrolling = true;
        Log.i(TAG, "enrolling this phone");
        ChromeTabbedActivity activity = sActivity;
        new Thread(
                        () -> {
                            LemurXAccount.ensureAeroTicket(activity);
                            boolean ok = MuseApi.enrollThisDevice();
                            ThreadUtils.runOnUiThread(
                                    () -> {
                                        sEnrolling = false;
                                        if (sPausedByUser || LemurXKillSwitch.isDisabled()) return;
                                        if (!ok) {
                                            sNextEnrollMs = System.currentTimeMillis() + 15_000;
                                            Log.w(TAG, "device enroll failed; retrying later");
                                            return;
                                        }
                                        if (sActivity != null) start();
                                    });
                        },
                        "LemurXEnroll")
                .start();
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

    // ---------------------------------------------------------------- 长连接（后台线程）

    private static void loop(int generation) {
        long backoff = BACKOFF_MIN_MS;
        try {
            while (sGeneration == generation) {
                // 读超时会留下中断标志。那只说明这条 socket 断了，stop() 才会改 generation。
                Thread.interrupted();
                String why = holdStream(generation);
                if (sGeneration != generation) break;
                if ("reenroll".equals(why)) break;
                if (why == null) {
                    backoff = BACKOFF_MIN_MS;
                    continue;
                }
                if ("disabled".equals(why)) break;
                if (sConnected || !why.equals(sLastError)) Log.w(TAG, "stream ended: %s", why);
                setConnected(false);
                sLastError = why;
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    if (sGeneration != generation) break;
                    Thread.interrupted();
                }
                backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
            }
        } catch (Throwable t) {
            Log.w(TAG, "bridge loop crashed", t);
        }
        Log.w(TAG, "bridge loop exit paused=%s", sPausedByUser);
        if (sGeneration == generation) setConnected(false);
    }

    /** 线程自己退出或代理把死连接撑着时拉起来。测试包关停就不要再连。 */
    private static void armWatchdog() {
        if (sWatchdogArmed) return;
        sWatchdogArmed = true;
        sWatchdog.postDelayed(sWatchdogTick, 3_000);
    }

    private static void watchdog() {
        try {
            if (sActivity == null || sPausedByUser || LemurXKillSwitch.isDisabled()) return;
            if (TextUtils.isEmpty(LemurXDeviceConfig.token())) {
                start();
                return;
            }
            boolean dead = sThread == null || !sThread.isAlive();
            boolean stale =
                    sLastPollMs > 0 && System.currentTimeMillis() - sLastPollMs > STALE_STREAM_MS;
            if (dead) {
                Log.w(TAG, "bridge thread gone; starting again");
                start();
            } else if (stale) {
                // 这次重连自己还没读到心跳。先把计时拨回来，否则看门狗每 3 秒拆掉还没握上的连接。
                Log.w(TAG, "stream stale; starting again");
                sLastPollMs = System.currentTimeMillis();
                stop();
                start();
            }
        } finally {
            sWatchdog.postDelayed(sWatchdogTick, 3_000);
        }
    }

    /**
     * 守住一条 {@code /lemurx/device/stream}。返回 null 表示正常结束还要立刻重连；其它字符串是失败原因。
     */
    private static @Nullable String holdStream(int generation) {
        String url;
        try {
            url =
                    LemurXDeviceConfig.server()
                            + "/lemurx/device/stream?fg="
                            + (foreground() ? "1" : "0")
                            + "&name="
                            + URLEncoder.encode(LemurXDeviceConfig.deviceName(), "UTF-8");
        } catch (Exception e) {
            url = LemurXDeviceConfig.server() + "/lemurx/device/stream";
        }
        HttpURLConnection connection = null;
        boolean reportedFg = foreground();
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(STREAM_READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty(
                    "Authorization", "Bearer " + LemurXDeviceConfig.token());
            applyIdentity(connection);
            connection.setRequestProperty("Accept", "text/event-stream");
            int status = connection.getResponseCode();
            if (status != 200) {
                String body = readLimited(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
                if (LemurXKillSwitch.isDisabledStatus(status, body)) {
                    Log.i(TAG, "test build disabled by server");
                    LemurXKillSwitch.activate();
                    return "disabled";
                }
                if (status == 401) {
                    // 旧的共享令牌，或换主人之后的令牌。清掉，循环退出后会重新登记。
                    LemurXDeviceConfig.setDeviceToken(null);
                    sNextEnrollMs = 0;
                    return "reenroll";
                }
                if (status == 404) return "server has no Aero bridge (404)";
                return "http " + status;
            }
            if (!sConnected) Log.i(TAG, "connected to %s", LemurXDeviceConfig.server());
            setConnected(true);
            sLastError = null;
            sLastPollMs = System.currentTimeMillis();
            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder data = new StringBuilder();
            String line;
            while (sGeneration == generation && (line = reader.readLine()) != null) {
                sLastPollMs = System.currentTimeMillis();
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        dispatchStream(data.toString(), generation);
                        data.setLength(0);
                    }
                    continue;
                }
                if (line.startsWith(":")) {
                    boolean fg = foreground();
                    if (fg != reportedFg) {
                        reportedFg = fg;
                        reportPresence(fg);
                    }
                    continue;
                }
                if (line.startsWith("data:")) {
                    String chunk = line.substring(5);
                    if (chunk.startsWith(" ")) chunk = chunk.substring(1);
                    if (data.length() > 0) data.append('\n');
                    data.append(chunk);
                }
            }
            return null;
        } catch (Exception e) {
            if (sGeneration != generation) return null;
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void dispatchStream(String json, int generation) {
        try {
            JSONArray commands = new JSONObject(json).optJSONArray("commands");
            if (commands == null) return;
            for (int i = 0; i < commands.length(); i++) {
                JSONObject cmd = commands.optJSONObject(i);
                if (cmd == null) continue;
                sCommandRunner.execute(
                        () -> {
                            if (sGeneration != generation) return;
                            handle(cmd);
                        });
            }
        } catch (Exception e) {
            Log.i(TAG, "stream event: %s", e.getMessage());
        }
    }

    private static void reportPresence(boolean foreground) {
        try {
            JSONObject body = new JSONObject();
            body.put("fg", foreground);
            body.put("name", LemurXDeviceConfig.deviceName());
            http("POST", LemurXDeviceConfig.server() + "/lemurx/device/presence",
                    body.toString(), HTTP_TIMEOUT_MS);
        } catch (Exception e) {
            Log.i(TAG, "presence: %s", e.getMessage());
        }
    }

    private static String readLimited(@Nullable InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int n;
            while (buffer.size() < 2048 && (n = in.read(chunk)) > 0) buffer.write(chunk, 0, n);
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
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

    /** 设备桥按游客 / 账号主人排队，密钥本身不打日志。 */
    private static void applyIdentity(HttpURLConnection connection) {
        String guest = MuseApi.guestKey();
        if (!TextUtils.isEmpty(guest)) connection.setRequestProperty("X-Aero-Guest", guest);
        String account = MuseApi.accountId();
        if (!TextUtils.isEmpty(account)) connection.setRequestProperty("X-Aero-Account", account);
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
            applyIdentity(connection);
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
            // 可见性契约：窗口不在前台，或这一轮已挂起时，不打开、不读、不操作网页。
            // 没有第二台浏览器接手。用户回到对话里点继续，才在这个标签上接着做。
            boolean touchesPage = path.startsWith("/sessions")
                    && !("GET".equals(method) && "/sessions".equals(path))
                    && !path.endsWith("/close") && !path.contains("/downloads");
            if (touchesPage && !foreground()) {
                return Reply.error(409, "DEVICE_BACKGROUND",
                        "Aero left the screen. The operation is suspended. Stop. The user resumes from the same tab.");
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
                    // 用户正在看的这一页优先，不回到以前另开的标签。
                    Tab existing = sActivity == null ? null : sActivity.getActivityTab();
                    if (existing == null) existing = findTab(session.tab);
                    if (existing != null) {
                        // 一个 Agent 一个标签：网址就在当前标签里打开，不再另开一页。
                        holdInTab(existing.getId());
                        existing.loadUrl(new LoadUrlParams(url));
                        if (bringFront) selectTab(existing);
                        return TabInfo.of(existing);
                    }
                    Tab opened = openTab(url, !bringFront);
                    if (opened != null) holdInTab(opened.getId());
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
        holdPage(tab.id, ContextUtils.getApplicationContext().getString(
                R.string.lemurx_agent_log_open, briefUrl(url)));
        waitUntilSettled(tab.id);
        TabInfo after = onUi(() -> {
            Tab t = findTab(tab.id);
            return t == null ? null : TabInfo.of(t);
        });
        String shownTitle = after != null && !TextUtils.isEmpty(after.title)
                ? after.title : briefUrl(url);
        String shownUrl = after != null && !TextUtils.isEmpty(after.url) ? after.url : url;
        note(ContextUtils.getApplicationContext().getString(
                R.string.lemurx_agent_log_open_done, clipLine(shownTitle, 40), briefUrl(shownUrl)));
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
                Tab t = findTab(sAnchorTab);
                if (t == null && sActivity != null) t = sActivity.getActivityTab();
                if (t != null) {
                    holdInTab(t.getId());
                    t.loadUrl(new LoadUrlParams(url));
                    if (foreground) selectTab(t);
                    return TabInfo.of(t);
                }
                t = openTab(url, !foreground);
                if (t != null) holdInTab(t.getId());
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
     * 只操作这个会话自己的标签。不跟着「当前前台」或最近一个网页跑，否则一点链接就串到新标签。
     */
    private static @Nullable TabInfo useContent(Session s) {
        final int preferred = s.tab;
        final boolean foreground = LemurXDeviceConfig.foreground();
        TabInfo tab = onUi(() -> {
            Tab chosen = findTab(preferred);
            if (chosen == null && sActivity != null) chosen = sActivity.getActivityTab();
            if (chosen == null) return null;
            holdInTab(chosen.getId());
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
        requireNoCaptcha();
        Session s = getSession(id);
        TabInfo tab = useContent(s);
        if (tab == null) {
            throw new SessionError(502, "READ_FAILED",
                    "The page did not return any text (still loading, or not an http page).");
        }
        holdPage(tab.id, ContextUtils.getApplicationContext().getString(
                R.string.lemurx_agent_log_read, pageName(tab.title, tab.url)));
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
        Context readContext = ContextUtils.getApplicationContext();
        if (rendererBlocked && dialog != null) {
            String message = dialog.optString("message", dialog.optString("title", ""));
            if (!message.isEmpty()) {
                note(readContext.getString(
                        R.string.lemurx_agent_log_read_dialog, clipLine(message, 80)));
            }
        } else if (snap != null) {
            String readTitle = !TextUtils.isEmpty(snap.title) ? snap.title : pageName(s.title, s.url);
            note(readContext.getString(
                    R.string.lemurx_agent_log_read_done,
                    clipLine(readTitle, 40), excerpt(snap.text)));
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
            attachCredentialHint(out, tab.id, out.optString("url", s.url), hasPasswordField(snap));
        } catch (Exception ignored) {
            // 键是常量
        }
        // 阅读停顿：真人读完一屏字再动手，正文越长停得越久（下一条输入前生效）。
        int len = snap == null ? 0 : snap.text.length();
        sDwellUntil = SystemClock.uptimeMillis() + (long) clamp(len / 50.0, 400, 2_500);
        considerCaptcha(tab.id, s.url, s.title, snap);
        return out;
    }

    /** GET /sessions/:id/elements：可操作元素清单（1280x800 画布坐标）。 */
    private static JSONObject elementsSession(String id) throws SessionError {
        requireNoCaptcha();
        Session s = getSession(id);
        TabInfo tab = useContent(s);
        if (tab == null) {
            throw new SessionError(502, "READ_FAILED", "The page is not ready.");
        }
        holdPage(tab.id, ContextUtils.getApplicationContext().getString(
                R.string.lemurx_agent_log_scan, pageName(tab.title, tab.url)));
        LemurXPage.Snapshot snap = snapshot(tab.id);
        if (snap == null) {
            throw new SessionError(502, "READ_FAILED", "The page is not ready.");
        }
        String examples = exampleNames(snap);
        Context scanContext = ContextUtils.getApplicationContext();
        note(examples.isEmpty()
                ? scanContext.getString(R.string.lemurx_agent_log_scan_unnamed, snap.elements.size())
                : scanContext.getString(
                        R.string.lemurx_agent_log_scan_done, snap.elements.size(), examples));
        JSONObject out = new JSONObject();
        try {
            out.put("url", snap.url);
            out.put("title", snap.title);
            out.put("elements", snap.elementsJson());
            out.put("focusId", snap.focusId);
            out.put("pageScale", Math.round(snap.pageScale * 100) / 100.0);
            JSONObject dialog = dialog(tab.id);
            if (dialog != null) out.put("dialog", dialog);
            attachCredentialHint(out, tab.id, snap.url, hasPasswordField(snap));
        } catch (Exception ignored) {
            // 键是常量
        }
        considerCaptcha(tab.id, snap.url, snap.title, snap);
        return out;
    }

    // ---------------------------------------------------------------- 截图

    private static String screenshotSession(String id) throws SessionError {
        requireNoCaptcha();
        Session s = getSession(id);
        TabInfo tab = useContent(s);
        if (tab == null) {
            throw new SessionError(502, "SCREENSHOT_FAILED", "Aero could not capture the tab.");
        }
        holdPage(tab.id, ContextUtils.getApplicationContext().getString(
                R.string.lemurx_agent_log_look, pageName(tab.title, tab.url)));
        considerCaptcha(tab.id, tab.url, tab.title, null);
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
        if (!"yield".equals(kind) && !"resume".equals(kind)) {
            requireNoCaptcha();
            String target = body.optString("name", "")
                    + " "
                    + body.optString("placeholder", "")
                    + " "
                    + body.optString("selector", "");
            if (captchaToken(target, false)) {
                considerCaptcha(tab.id, tab.url, tab.title, null);
            }
            // 先锁住整屏再动手，底栏和页面在接管之前都不能点。
            // 对话框把页面上的原文写进这一步，点按钮之前用户能看到在回应哪一句。
            holdPage(tab.id, "dialog".equals(kind) ? describeDialog(tab.id, body) : describeInput(body));
        }
        if (!"yield".equals(kind)) {
            // 用户填完验证码之后 Agent 接着干：先收回页面，再按住系统键盘。
            if (sYielded) {
                onUi(
                        () -> {
                            resumeDriving();
                            return true;
                        });
            }
            onUi(
                    () -> {
                        if (isDriving()) forceHideSystemIme(webContentsForTab(tab.id));
                        return true;
                    });
        }
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
        } else if ("select".equals(kind) && body.has("text")) {
            String text = body.optString("text", "");
            if (text.isEmpty() || text.length() > 500) {
                throw new SessionError(400, "INVALID_INPUT", "Unsupported browser input.");
            }
            pace();
            if (body.has("x") && body.has("y")) {
                float[] vp = viewportPx(tab.id);
                if (vp == null) {
                    throw new SessionError(502, "INPUT_FAILED", "The page is not ready for input.");
                }
                float x = (float) (clamp(body.optDouble("x", 0), 0, CONSOLE_W - 1) / CONSOLE_W * vp[0]);
                float y = (float) (clamp(body.optDouble("y", 0), 0, CONSOLE_H - 1) / CONSOLE_H * vp[1]);
                gestureTap(tab.id, x, vp[2] + y, true);
                sleep(300);
            }
            int r = selectFieldText(tab.id, text);
            if (r == TYPE_NO_FOCUS) {
                throw new SessionError(409, "NO_INPUT_FOCUS",
                        "No text field is focused. Click the field first, then select.");
            }
            if (r != TYPE_OK) {
                throw new SessionError(404, "TEXT_NOT_FOUND",
                        "That text is not in the focused field.");
            }
            ok = true;
        } else if ("yield".equals(kind)) {
            String reason = body.optString("reason", "");
            if (reason.length() > 200) reason = reason.substring(0, 200);
            yieldToUser(tab.id, reason);
            ok = true;
        } else if ("resume".equals(kind)) {
            onUi(
                    () -> {
                        resumeDriving();
                        return true;
                    });
            ok = true;
        } else if ("text".equals(kind) && body.has("text")) {
            String text = body.optString("text", "");
            if (text.length() > 10_000) {
                throw new SessionError(400, "INVALID_INPUT", "Unsupported browser input.");
            }
            pace();
            LemurXPage.Element focusedField = null;
            if (body.has("target")) {
                // 先把焦点点进目标输入框（已经聚焦就不再点）。
                focusedField = focusTarget(tab.id, body.opt("target"));
                resolved = LemurXPage.brief(focusedField);
            }
            // 密码框：库里有账号就只按引用在本机填，云端传来的正文丢掉。库里没有才用这一次的输入，并且不回传。
            if (isPasswordField(tab.id, body, focusedField)) {
                redactSecretLog(text);
                int saved = savedLoginKind(tab.id, body.optString("username", ""));
                if (saved == LemurXPasswords.ONE || saved == LemurXPasswords.MANY) {
                    fillSavedLogin(tab.id, body.optString("username", ""));
                    ok = true;
                } else {
                    sFieldChecked = false;
                    sFieldMatched = false;
                    sFieldValue = null;
                    int r = typeText(tab.id, text);
                    if (r == TYPE_NO_FOCUS) {
                        hideKeyboard();
                        throw new SessionError(409, "NO_INPUT_FOCUS",
                                "No text field is focused. Click the field first, then type.");
                    }
                    ok = r == TYPE_OK;
                    sFieldChecked = false;
                    sFieldValue = null;
                }
                sFieldChecked = false;
                sFieldMatched = false;
                sFieldValue = null;
            } else {
                sFieldChecked = false;
                sFieldMatched = false;
                sFieldValue = null;
                int r = typeText(tab.id, text);
                if (r == TYPE_NO_FOCUS) {
                    hideKeyboard();
                    throw new SessionError(409, "NO_INPUT_FOCUS",
                            "No text field is focused. Click the field first, then type.");
                }
                ok = r == TYPE_OK;
            }
        } else if ("key".equals(kind) && body.has("key")) {
            String name = body.optString("key", "");
            if (!LemurXHumanInput.isKnownKey(name)) {
                throw new SessionError(400, "INVALID_INPUT", "Unsupported key.");
            }
            pace();
            ok = pressKey(tab.id, name);
        } else if ("login".equals(kind)) {
            fillSavedLogin(tab.id, body.optString("username", ""));
            ok = true;
        } else if ("list_credentials".equals(kind)) {
            // 只回不透明引用 + 用户名标签；口令永不离开本机。
            return listCredentialRefs(tab.id);
        } else if ("fill_secret".equals(kind)) {
            // 云端只给 ref + 1-based index；本机取值填入，回执不含字符本身。
            return fillSecretRef(tab.id, body);
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
        // 点中之后补一句真正点到的控件。请求里往往只有编号，名字要等页面认出来才有。
        if ("click".equals(kind) && resolved != null) {
            String phrase = targetPhrase(resolved);
            if (!phrase.isEmpty()) {
                note(ContextUtils.getApplicationContext().getString(
                        R.string.lemurx_agent_log_clicked, phrase));
            }
        } else if ("text".equals(kind) && resolved != null
                && !secretTarget(body.opt("target")) && !secretTarget(resolved)) {
            String phrase = targetPhrase(resolved);
            String typed = clipLine(body.optString("text", ""), 48);
            if (!phrase.isEmpty() && !typed.isEmpty()) {
                note(ContextUtils.getApplicationContext().getString(
                        R.string.lemurx_agent_log_type, phrase, typed));
            }
        }
        showBanner(tab.id, ContextUtils.getApplicationContext().getString(
                R.string.lemurx_agent_working));
        JSONObject view = s.view();
        try {
            if (resolved != null) view.put("target", resolved);
            if ("text".equals(kind) && sFieldChecked) {
                view.put("fieldMatched", sFieldMatched);
                // 对上了就不把内容再带回服务端。没对上才带回去，让模型改。密码永远不带回。
                if (!sFieldMatched && sFieldValue != null && !isPasswordField(tab.id, body, null)) {
                    view.put("fieldValue", sFieldValue);
                }
            }
            if (sLoginRef != null) {
                view.put("ref", sLoginRef);
                view.put("receipt", "filled on device from " + sLoginRef);
                sLoginRef = null;
            }
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

    /** 快照里有没有密码框。没有快照时不当成登录页。 */
    private static boolean hasPasswordField(LemurXPage.@Nullable Snapshot snap) {
        if (snap == null) return false;
        for (LemurXPage.Element element : snap.elements) {
            if ("password".equalsIgnoreCase(element.inputType)) return true;
        }
        return false;
    }

    /**
     * 这一下是不是在填密码。云端只应拿到引用；真正的字由本机密码库填。
     * 目标已经解析出来就看它的类型，否则看当前焦点。
     */
    private static boolean isPasswordField(
            int tabId, JSONObject body, LemurXPage.@Nullable Element resolved) {
        if (secretTarget(body.opt("target"))) return true;
        if (resolved != null) return "password".equalsIgnoreCase(resolved.inputType);
        if (body.has("target")) return false;
        LemurXPage.Snapshot snap = snapshot(tabId);
        LemurXPage.Element focused = snap == null ? null : snap.focused();
        return focused != null && "password".equalsIgnoreCase(focused.inputType);
    }

    /** 这个标签页的密码库里有没有可用账号。NONE / ONE / MANY，不取出明文。 */
    private static int savedLoginKind(int tabId, String username) {
        String pageUrl = onUi(() -> {
            Tab t = findTab(tabId);
            if (t == null || t.getUrl() == null) return "";
            return t.getUrl().getSpec();
        });
        Profile profile = onUi(() -> {
            Tab t = findTab(tabId);
            return t == null ? null : t.getProfile();
        });
        if (profile == null || pageUrl == null || pageUrl.isEmpty()) return LemurXPasswords.NONE;
        return LemurXPasswords.lookup(profile, pageUrl, username == null ? "" : username).kind;
    }

    /** 操作记录里如果带上了密码正文，换成一句不含内容的话。 */
    private static void redactSecretLog(@Nullable String secret) {
        if (secret == null || secret.isEmpty() || sActionLog.isEmpty()) return;
        String clipped = clipLine(secret, 48);
        if (clipped.isEmpty()) return;
        int last = sActionLog.size() - 1;
        if (!sActionLog.get(last).contains(clipped)) return;
        sActionLog.set(
                last,
                ContextUtils.getApplicationContext().getString(R.string.lemurx_agent_log_type_secret));
        publishActionLog();
    }

    /**
     * 告诉云端这个网站在本机有哪些账号引用。只有 ref、用户名和是否主要账号，没有口令。
     * 填写必须走本机的 login，云端不能把密码再传回来。
     */
    private static void attachCredentialHint(
            JSONObject out, int tabId, @Nullable String pageUrl, boolean passwordField) {
        if (pageUrl == null || pageUrl.isEmpty()) return;
        Profile profile = onUi(() -> {
            Tab t = findTab(tabId);
            return t == null ? null : t.getProfile();
        });
        if (profile == null) return;
        java.util.List<LemurXPasswords.Ref> refs = LemurXPasswords.listRefs(profile, pageUrl);
        try {
            if (!refs.isEmpty()) {
                JSONArray list = new JSONArray();
                for (LemurXPasswords.Ref ref : refs) {
                    JSONObject row = new JSONObject();
                    row.put("ref", ref.ref);
                    row.put("host", ref.host);
                    row.put("username", ref.username);
                    row.put("primary", ref.primary);
                    list.put(row);
                }
                out.put("credentials", list);
                out.put(
                        "credentialHint",
                        "Opaque refs for accounts saved on this phone. Fill with input "
                                + "{\"type\":\"login\"} or {\"type\":\"login\",\"username\":\"...\"}. "
                                + "The phone looks the password up and types it. Do not send, request, "
                                + "or repeat the password.");
                return;
            }
            if (passwordField) {
                out.put(
                        "credentialHint",
                        "No Aero login is saved for this site. Do not send the password. "
                                + "After a successful sign-in the phone asks to store it on device.");
            }
        } catch (Exception ignored) {
            // 键是常量
        }
    }

    /** 当前页可填的不透明凭证引用。返回值绝无 password 字段。 */
    private static JSONObject listCredentialRefs(int tabId) throws SessionError {
        String pageUrl = onUi(() -> {
            Tab t = findTab(tabId);
            if (t == null) return "";
            GURL url = t.getUrl();
            return url == null ? "" : url.getSpec();
        });
        Profile profile = onUi(() -> {
            Tab t = findTab(tabId);
            return t == null ? null : t.getProfile();
        });
        if (profile == null || pageUrl == null || pageUrl.isEmpty()) {
            throw new SessionError(502, "INPUT_FAILED", "The page is not ready for input.");
        }
        java.util.List<LemurXPasswords.Ref> refs = LemurXPasswords.listRefs(profile, pageUrl);
        JSONObject view = new JSONObject();
        try {
            JSONArray list = new JSONArray();
            for (LemurXPasswords.Ref ref : refs) {
                JSONObject row = new JSONObject();
                row.put("ref", ref.ref);
                row.put("host", ref.host);
                row.put("username", ref.username);
                row.put("primary", ref.primary);
                list.put(row);
            }
            view.put("credentials", list);
            view.put("receipt", "listed " + list.length() + " credential refs");
        } catch (Exception ignored) {
            // 键是常量
        }
        return view;
    }

    /**
     * 引用协议：云端传来 {@code ref} + 1-based {@code index}，本机解析出那一个字符填入焦点框，
     * 只回 {@code receipt}（例如 filled digit 3）。解析值不进日志、不进返回 JSON、不进崩溃上报字段。
     */
    private static JSONObject fillSecretRef(int tabId, JSONObject body) throws SessionError {
        String ref = body.optString("ref", "").trim();
        int index = body.optInt("index", 0);
        if (!LemurXPasswords.isPasswordRef(ref)) {
            throw new SessionError(400, "INVALID_INPUT", "A credential ref is required.");
        }
        if (index < 1 || index > 256) {
            throw new SessionError(400, "INVALID_INPUT", "index must be a 1-based digit position.");
        }
        Profile profile = onUi(() -> {
            Tab t = findTab(tabId);
            return t == null ? null : t.getProfile();
        });
        if (profile == null) {
            throw new SessionError(502, "INPUT_FAILED", "The page is not ready for input.");
        }
        LemurXPasswords.Lookup found = LemurXPasswords.lookupRef(profile, ref);
        if (found.kind == LemurXPasswords.MANY) {
            throw new SessionError(409, "MULTIPLE_ACCOUNTS",
                    "Several saved accounts match this ref: " + found.choices
                            + ". Pass a ref that includes the username.");
        }
        if (found.kind != LemurXPasswords.ONE || found.login == null) {
            throw new SessionError(404, "NO_SAVED_LOGIN",
                    "No saved password for this ref on this phone.");
        }
        String secret = found.login.password;
        found.login = null;
        if (secret == null || index > secret.length()) {
            throw new SessionError(409, "INDEX_OUT_OF_RANGE",
                    "That digit index is outside the saved secret.");
        }
        String ch = secret.substring(index - 1, index);
        secret = null;
        try {
            if (body.has("target")) {
                focusTarget(tabId, body.opt("target"));
            }
            sFieldChecked = false;
            sFieldMatched = false;
            sFieldValue = null;
            int typed = typeText(tabId, ch);
            ch = null;
            if (typed == TYPE_NO_FOCUS) {
                hideKeyboard();
                throw new SessionError(409, "NO_INPUT_FOCUS",
                        "No text field is focused. Click the field first, then fill the digit.");
            }
            if (typed != TYPE_OK) {
                throw new SessionError(502, "INPUT_FAILED", "Could not type the referenced digit.");
            }
        } finally {
            ch = null;
            secret = null;
            sFieldValue = null;
            sFieldChecked = false;
            sFieldMatched = false;
        }
        JSONObject view = new JSONObject();
        try {
            view.put("receipt", "filled digit " + index);
            view.put("ref", ref);
            view.put("index", index);
        } catch (Exception ignored) {
            // 键是常量
        }
        return view;
    }

    /**
     * 用本机密码库里这个站点的账号填登录框。密码只进输入法，不进返回值。
     * username 为空且只存了一个账号时用那一个；存了多个就报用户名让调用方再选。
     */
    private static void fillSavedLogin(int tabId, String username) throws SessionError {
        String pageUrl = onUi(() -> {
            Tab t = findTab(tabId);
            if (t == null) return "";
            GURL url = t.getUrl();
            return url == null ? "" : url.getSpec();
        });
        Profile profile = onUi(() -> {
            Tab t = findTab(tabId);
            return t == null ? null : t.getProfile();
        });
        if (profile == null || pageUrl == null || pageUrl.isEmpty()) {
            throw new SessionError(502, "INPUT_FAILED", "The page is not ready for input.");
        }
        LemurXPasswords.Lookup found = LemurXPasswords.lookup(profile, pageUrl, username);
        if (found.kind == LemurXPasswords.MANY) {
            throw new SessionError(409, "MULTIPLE_ACCOUNTS",
                    "Several saved accounts for this site: " + found.choices
                            + ". Call login again with username set to one of them.");
        }
        if (found.kind != LemurXPasswords.ONE || found.login == null) {
            throw new SessionError(404, "NO_SAVED_LOGIN",
                    "No saved Aero login for this site. Sign in once and accept the on-device save prompt.");
        }
        LemurXPasswords.Login login = found.login;
        String filledRef = LemurXPasswords.refOf(LemurXPasswords.hostOf(pageUrl), login.username);
        try {
            LemurXPage.Snapshot snap = snapshot(tabId);
            if (snap == null) {
                throw new SessionError(502, "INPUT_FAILED", "The page is not ready for input.");
            }
            LemurXPage.Element password = null;
            LemurXPage.Element nameField = null;
            for (LemurXPage.Element e : snap.elements) {
                if (e.disabled) continue;
                if ("password".equalsIgnoreCase(e.inputType)) {
                    if (password == null || (password.offscreen && !e.offscreen)) password = e;
                    continue;
                }
                if (password != null || !e.editable || "searchBox".equals(e.role)) continue;
                if (!"textField".equals(e.role) && !"email".equalsIgnoreCase(e.inputType)
                        && !"tel".equalsIgnoreCase(e.inputType)
                        && !"text".equalsIgnoreCase(e.inputType)) {
                    continue;
                }
                nameField = e;
            }
            if (password == null) {
                throw new SessionError(409, "NO_LOGIN_FORM",
                        "No password field is on this page.");
            }
            caption("填写已保存的登录");
            if (nameField != null && !login.username.isEmpty()) {
                focusTarget(tabId, nameField.id);
                int typed = typeText(tabId, login.username);
                if (typed == TYPE_NO_FOCUS) {
                    throw new SessionError(409, "NO_INPUT_FOCUS",
                            "Could not focus the username field.");
                }
                if (typed != TYPE_OK) {
                    throw new SessionError(502, "INPUT_FAILED", "Could not type the username.");
                }
            }
            focusTarget(tabId, password.id);
            int typed = typeText(tabId, login.password);
            if (typed == TYPE_NO_FOCUS) {
                throw new SessionError(409, "NO_INPUT_FOCUS",
                        "Could not focus the password field.");
            }
            if (typed != TYPE_OK) {
                throw new SessionError(502, "INPUT_FAILED", "Could not type the saved password.");
            }
            sLoginRef = filledRef;
        } finally {
            // 打字核对会把框里的字记下来给模型改。登录不能把密码带回服务端。
            sFieldValue = null;
            sFieldChecked = false;
            sFieldMatched = false;
            found.login = null;
        }
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

    /** 输入法通道打字，打完读回输入框。对不上就退格或长按选中错的那一段，只改那一段。 */
    private static int typeText(int tabId, String text) {
        WebContents wc = onUi(() -> {
            Tab t = findTab(tabId);
            if (t == null) return null;
            WebContents w = t.getWebContents();
            if (w != null) holdIme(w, t.getContentView());
            return w;
        });
        if (wc == null) return TYPE_FAILED;
        try {
            if (!LemurXHumanInput.hasEditableFocus(wc)) return TYPE_NO_FOCUS;
            LemurXHumanInput.FieldSnap before = LemurXHumanInput.fieldSnap(inputConnection(wc));
            LemurXAgentKeyboard keys = onUi(() -> showKeyboard(30_000, wc));
            awaitImeHidden();
            boolean ok = LemurXHumanInput.typeText(wc, text, LemurXDeviceBridge::userActive, keys);
            if (!ok) return TYPE_FAILED;
            String want = wantField(before, text);
            sleep(220);
            String after = LemurXHumanInput.fieldText(inputConnection(wc));
            if (after == null) {
                Log.i(TAG, "field check unread want=%d", want.length());
                return TYPE_OK;
            }
            sFieldChecked = true;
            sFieldValue = after;
            if (sameField(after, want)) {
                sFieldMatched = true;
                Log.i(TAG, "field check matched len=%d", after.length());
                return TYPE_OK;
            }
            Log.i(TAG, "field check mismatch before=%d sel=%d after=%d want=%d",
                    before == null ? -1 : before.text().length(),
                    before == null ? -1 : before.selected.length(),
                    after.length(), want.length());
            if (userActive()) return TYPE_OK;
            fixField(tabId, wc, after, want, keys);
            sleep(220);
            String again = LemurXHumanInput.fieldText(inputConnection(wc));
            if (again == null) return TYPE_OK;
            sFieldValue = again;
            sFieldMatched = sameField(again, want);
            Log.i(TAG, "field check retried matched=%s len=%d", sFieldMatched, again.length());
            return TYPE_OK;
        } finally {
            ThreadUtils.runOnUiThread(LemurXDeviceBridge::releaseIme);
        }
    }

    /**
     * 打完之后整框该是什么。有选区就是替换那一段（browser_select 之后的改字）；
     * 没选区就插在光标处。不要再把「短框 = 整框变成这次打的那几个字」——模型改一段错字时
     * 只会传来替换片段，整框对成片段就会把刚填对的字删掉，然后选、打、删死循环。
     */
    private static String wantField(
            LemurXHumanInput.@Nullable FieldSnap before, String typed) {
        if (before == null) return typed;
        if (before.hasSelection()) return before.before + typed + before.after;
        if (before.text().isEmpty() || sameField(before.text(), typed)) return typed;
        return before.before + typed + before.after;
    }

    private static boolean sameField(String actual, String intended) {
        return actual.replace("\r\n", "\n").trim().equals(intended.replace("\r\n", "\n").trim());
    }

    /**
     * 像人一样改错：只修尾巴（退格再补）或在对上的前缀后面插入。中间一段对不上就停，
     * 留给模型自己 browser_select，不再长按——长按选中的往往是词不是算出来的区间，
     * 填对之后又被下一次核对当成整框错字删掉。
     */
    private static void fixField(
            int tabId, WebContents wc, String actual, String want, @Nullable LemurXAgentKeyboard keys) {
        int prefix = commonPrefix(actual, want);
        int suffix = commonSuffix(actual, want, prefix);
        String bad = actual.substring(prefix, actual.length() - suffix);
        String good = want.substring(prefix, want.length() - suffix);
        InputConnection ic = inputConnection(wc);
        if (ic == null) return;
        if (bad.isEmpty()) {
            LemurXHumanInput.placeCursor(ic, prefix);
            sleep(160);
            if (!good.isEmpty()) {
                LemurXHumanInput.typeText(wc, good, LemurXDeviceBridge::userActive, keys);
            }
            return;
        }
        // 只是末尾多了或错了几个字：退格，再补上。
        if (suffix == 0 && bad.length() <= 16) {
            for (int i = 0; i < bad.length() && !userActive(); i++) {
                if (keys != null) keys.pressKey("Backspace");
                LemurXHumanInput.backspace(ic);
                sleep(70 + (int) (Math.random() * 50));
            }
            if (!good.isEmpty() && !userActive()) {
                LemurXHumanInput.typeText(wc, good, LemurXDeviceBridge::userActive, keys);
            }
            return;
        }
        Log.i(TAG, "field check skip mid-span fix prefix=%d suffix=%d bad=%d",
                prefix, suffix, bad.length());
    }

    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    private static int commonSuffix(String a, String b, int prefix) {
        int i = 0;
        int aLimit = a.length() - prefix;
        int bLimit = b.length() - prefix;
        while (i < aLimit && i < bLimit
                && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) {
            i++;
        }
        return i;
    }

    /** 长按当前输入框，让选中看起来是手指按出来的。 */
    private static void longPressFocused(int tabId) {
        LemurXPage.Snapshot snap = snapshot(tabId);
        if (snap == null) return;
        LemurXPage.Element focused = snap.focused();
        if (focused == null || focused.px.isEmpty()) return;
        gestureTap(tabId, focused.cx(), focused.cy(), true);
    }

    /**
     * 在聚焦的输入框里长按并选中 {@code text}。找不到返回 {@link #TYPE_FAILED}，
     * 没有焦点返回 {@link #TYPE_NO_FOCUS}。
     */
    private static int selectFieldText(int tabId, String text) {
        WebContents wc = onUi(() -> {
            Tab t = findTab(tabId);
            return t == null ? null : t.getWebContents();
        });
        if (wc == null) return TYPE_FAILED;
        if (!LemurXHumanInput.hasEditableFocus(wc)) return TYPE_NO_FOCUS;
        InputConnection ic = inputConnection(wc);
        String field = LemurXHumanInput.fieldText(ic);
        if (field == null || ic == null) return TYPE_FAILED;
        int at = field.indexOf(text);
        if (at < 0) return TYPE_FAILED;
        // 长按先结束（系统会先选中一个词），再套上算出来的区间，避免手势把区间改回去。
        longPressFocused(tabId);
        sleep(160);
        LemurXHumanInput.selectRange(ic, at, at + text.length());
        sleep(160);
        LemurXHumanInput.FieldSnap snap = LemurXHumanInput.fieldSnap(ic);
        if (snap == null || !text.equals(snap.selected)) {
            LemurXHumanInput.selectRange(ic, at, at + text.length());
            sleep(80);
        }
        sFieldChecked = true;
        sFieldValue = text;
        sFieldMatched = true;
        return TYPE_OK;
    }

    private static @Nullable InputConnection inputConnection(WebContents wc) {
        ImeAdapter ime = ImeAdapter.fromWebContents(wc);
        return ime == null ? null : ime.getActiveInputConnection();
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
            LemurXAgentKeyboard keys = onUi(() -> showKeyboard(2_500, wc));
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
    private static @Nullable LemurXAgentKeyboard showKeyboard(long idleMs, @Nullable WebContents wc) {
        ThreadUtils.assertOnUiThread();
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return null;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return null;
        // 系统输入法是另一个窗口，压在我们上面。按住它，屏幕上只留这一块。
        setImeSuppressed(wc, true);
        for (int i = content.getChildCount() - 1; i >= 0; i--) {
            View child = content.getChildAt(i);
            if (child instanceof LemurXAgentKeyboard && child != sKeyboard) content.removeViewAt(i);
        }
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
        scheduleHideKeyboard(idleMs);
        return sKeyboard;
    }

    /** 按住或放开系统输入法。UI 线程。网页还在时才动。 */
    private static void setImeSuppressed(@Nullable WebContents wc, boolean suppress) {
        ThreadUtils.assertOnUiThread();
        if (!suppress) {
            WebContents prev = sSuppressedIme;
            sSuppressedIme = null;
            if (prev != null && !prev.isDestroyed()) {
                ImeAdapter ime = ImeAdapter.fromWebContents(prev);
                if (ime != null) ime.setKeyboardSuppressed(false);
            }
            return;
        }
        if (wc == null || wc.isDestroyed()) return;
        if (sSuppressedIme != null && sSuppressedIme != wc) setImeSuppressed(null, false);
        ImeAdapter ime = ImeAdapter.fromWebContents(wc);
        if (ime != null) ime.setKeyboardSuppressed(true);
        sSuppressedIme = wc;
    }

    /** 打字开始：按住系统输入法，并在打完之前反复把它按下去。UI 线程。 */
    private static void holdIme(@Nullable WebContents wc, @Nullable View view) {
        ThreadUtils.assertOnUiThread();
        sImeHold++;
        sImeView = view;
        forceHideSystemIme(wc);
        sWatchdog.removeCallbacks(sImeWatch);
        sWatchdog.post(sImeWatch);
    }

    /** 打字结束。还有别的打字没完就先不放开。UI 线程。 */
    private static void releaseIme() {
        ThreadUtils.assertOnUiThread();
        if (sImeHold > 0) sImeHold--;
        if (sImeHold == 0) scheduleHideKeyboard(800);
    }

    private static void watchIme() {
        if (sImeHold <= 0 && !isDriving()) return;
        WebContents wc = sSuppressedIme;
        if ((wc == null || wc.isDestroyed()) && isDriving()) wc = webContentsForTab(sBannerTab);
        forceHideSystemIme(wc);
        sWatchdog.postDelayed(sImeWatch, 200);
    }

    /** Agent 锁着页面、且没有把填写权交给用户。这时整屏包括底栏都不能点。 */
    public static boolean isDriving() {
        View lock = sBanner;
        return lock != null && lock.getVisibility() == View.VISIBLE && !sYielded;
    }

    private static @Nullable WebContents webContentsForTab(int tabId) {
        ThreadUtils.assertOnUiThread();
        Tab tab = findTab(tabId);
        return tab == null ? null : tab.getWebContents();
    }

    /** 系统输入法从哪扇窗弹出来都收掉。UI 线程。 */
    private static void forceHideSystemIme(@Nullable WebContents wc) {
        ThreadUtils.assertOnUiThread();
        setImeSuppressed(wc, true);
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (controller != null) controller.hide(WindowInsets.Type.ime());
        }
        InputMethodManager imm =
                (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        hideImeToken(imm, activity.getWindow().getDecorView());
        hideImeToken(imm, activity.findViewById(android.R.id.content));
        hideImeToken(imm, sImeView);
    }

    private static void hideImeToken(InputMethodManager imm, @Nullable View view) {
        if (view == null) return;
        android.os.IBinder token = view.getWindowToken();
        if (token != null) imm.hideSoftInputFromWindow(token, 0);
    }

    /**
     * 等系统输入法真正收起再开始打字（最多 800 ms，再留 150 ms 让它收尾）。Gboard 在收起时会
     * 对 InputConnection 调 finishComposingText；如果这时我们的第一个字正在组合中，就会被它
     * 提前提交，然后下一次 setComposingText 又插入一遍——首字母重复。桥接线程调用。
     */
    private static void awaitImeHidden() {
        long deadline = SystemClock.uptimeMillis() + 800;
        while (SystemClock.uptimeMillis() < deadline) {
            ThreadUtils.runOnUiThread(() -> forceHideSystemIme(sSuppressedIme));
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
        // 字还没打完。点击那一头的延迟回调不能在这时把键盘拆掉，否则系统键盘会弹回来。
        if (sImeHold > 0 && !userActive() && !sYielded) {
            scheduleHideKeyboard(800);
            return;
        }
        View k = sKeyboard;
        sKeyboard = null;
        sImeView = null;
        if (isDriving()) {
            // 还在接管：只收 agent 键盘，系统输入法继续按住。
            forceHideSystemIme(sSuppressedIme);
        } else {
            setImeSuppressed(null, false);
        }
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
                sImeView = view;
                forceHideSystemIme(wc);
                for (long delay : new long[] {200, 800}) {
                    ThreadUtils.postOnUiThreadDelayed(() -> {
                        if (userActive()) {
                            hideKeyboardNow();
                            return;
                        }
                        if (LemurXHumanInput.hasEditableFocus(wc)) {
                            showKeyboard(30_000, wc);
                        } else if (delay == 800 && sImeHold == 0 && sKeyboard == null) {
                            // 没点进输入框：Agent 还在接管就继续按住系统键盘，不要弹给用户。
                            if (!isDriving()) setImeSuppressed(null, false);
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

    /** 底栏 / 覆盖层把自己抬到最前之后，把锁和手指层再抬回去。锁的高度压过底栏。 */
    public static void keepLockOnTop() {
        ThreadUtils.assertOnUiThread();
        if (sLiftingLock) return;
        View lock = sBanner;
        if (lock == null || lock.getVisibility() != View.VISIBLE) return;
        sLiftingLock = true;
        try {
            lock.bringToFront();
            if (sOverlay != null) sOverlay.bringToFront();
        } finally {
            sLiftingLock = false;
        }
    }

    static boolean userActive() {
        return sUserFingerDown || SystemClock.uptimeMillis() < sUserTouchUntil;
    }

    /** 这个窗口是不是在前台，并且这一轮没有被用户挂起。 */
    static boolean foreground() {
        ChromeTabbedActivity activity = sActivity;
        if (sAgentSuspended || activity == null) return false;
        if (inAgentPip(activity)) return true;
        return ApplicationStatus.getStateForActivity(activity) == ActivityState.RESUMED;
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

    /** 记下这个 Agent 的标签，并开始把页面弹出的新标签折回这一页。UI 线程。 */
    private static void holdInTab(int tabId) {
        ThreadUtils.assertOnUiThread();
        if (tabId == Tab.INVALID_TAB_ID) return;
        sAnchorTab = tabId;
        sHoldInTab = true;
        watchNewTabs();
        noteAgentPresence();
    }

    /** 这个 Agent 正在用的标签。对话里用来显示「开了几个页面」。 */
    public static int anchorTabId() {
        return sAnchorTab;
    }

    /** 仍留在这个 Agent 名下的网页标签数。跳转被折回当前标签时就是 1。 */
    public static int pageCount() {
        ThreadUtils.assertOnUiThread();
        Tab tab = findTab(sAnchorTab);
        return tab != null && isWeb(tab) ? 1 : 0;
    }

    public static String pageHost() {
        ThreadUtils.assertOnUiThread();
        Tab tab = findTab(sAnchorTab);
        if (tab == null || tab.getUrl() == null) return "";
        String host = tab.getUrl().getHost();
        return host == null ? "" : host;
    }

    /** 这一轮是不是还占着标签。先读再 {@link #releaseScreen}。 */
    public static boolean isHoldingTab() {
        return sHoldInTab;
    }

    /** 操作结束或要用户回答：放开屏幕锁。对话由控制台盖回来。 */
    public static void releaseScreen() {
        sHoldInTab = false;
        hideBanner();
        noteAgentPresence();
    }

    private static void watchNewTabs() {
        ThreadUtils.assertOnUiThread();
        TabModelSelector selector = selector();
        if (selector == null || selector == sWatchedTabs) return;
        selector.getModel(false).addObserver(new TabModelObserver() {
            @Override
            public void didAddTab(
                    Tab tab,
                    @TabLaunchType int type,
                    @TabCreationState int creationState,
                    boolean markedForSelection) {
                if (!sHoldInTab || sFolding || sYielded) return;
                if (tab.getId() == sAnchorTab) return;
                if (isHttp(tab)) foldNow(tab);
                else foldWhenReady(tab);
            }
        });
        sWatchedTabs = selector;
    }

    /** 新标签的网址还没到：等到第一次 http(s) 再折回锚点，避免 about:blank 把当前页盖掉。 */
    private static void foldWhenReady(Tab extra) {
        extra.addObserver(new TabObserver() {
            @Override
            public void onUrlUpdated(Tab tab) {
                if (!isHttp(tab)) return;
                tab.removeObserver(this);
                foldNow(tab);
            }

            @Override
            public void onPageLoadStarted(Tab tab, GURL url) {
                String spec = url == null ? "" : url.getSpec();
                if (!spec.startsWith("http://") && !spec.startsWith("https://")) return;
                tab.removeObserver(this);
                foldNow(tab);
            }
        });
    }

    /** 页面用 target=_blank / window.open 弹出的标签：网址载入当前标签，然后关掉新标签。 */
    private static void foldNow(Tab extra) {
        ThreadUtils.assertOnUiThread();
        if (!sHoldInTab || sFolding || extra.getId() == sAnchorTab) return;
        Tab anchor = findTab(sAnchorTab);
        if (anchor == null || !isHttp(extra)) return;
        String url = extra.getUrl().getSpec();
        sFolding = true;
        try {
            anchor.loadUrl(new LoadUrlParams(url));
            selectTab(anchor);
            closeTab(extra);
        } finally {
            sFolding = false;
        }
    }

    private static boolean isHttp(Tab tab) {
        String url;
        try {
            url = tab.getUrl() == null ? "" : tab.getUrl().getSpec();
        } catch (Exception e) {
            return false;
        }
        return url.startsWith("http://") || url.startsWith("https://");
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

    // ---------------------------------------------------------------- 接管胶囊 / 步骤日志（UI 线程）

    /** 记下这一步，并锁住页面。对话里的日志和网页上的面板读同一条。 */
    private static void holdPage(int tabId, String line) {
        note(line);
        showBanner(tabId, line);
    }

    private static void note(String line) {
        if (line == null || line.isEmpty()) return;
        ThreadUtils.runOnUiThread(() -> {
            // 同一句连着出现（反复看同一页）不再往下堆，屏幕上仍停在这一步。
            if (!sActionLog.isEmpty() && line.equals(sActionLog.get(sActionLog.size() - 1))) {
                publishActionLog();
                return;
            }
            sActionLog.add(line);
            while (sActionLog.size() > 40) sActionLog.remove(0);
            publishActionLog();
        });
    }

    /** 新的一轮对话开始时清掉上一轮的步骤。 */
    public static void clearActionLog() {
        ThreadUtils.runOnUiThread(() -> {
            sActionLog.clear();
            publishActionLog();
        });
    }

    public static void addActionListener(Runnable listener) {
        if (listener == null || sActionListeners.contains(listener)) return;
        sActionListeners.add(listener);
    }

    public static void removeActionListener(Runnable listener) {
        sActionListeners.remove(listener);
    }

    public static ArrayList<String> actionLog() {
        return new ArrayList<>(sActionLog);
    }

    private static void publishActionLog() {
        ThreadUtils.assertOnUiThread();
        paintActionLog();
        for (Runnable listener : new ArrayList<>(sActionListeners)) {
            try {
                listener.run();
            } catch (Exception ignored) {
                // 对话视图拆掉了也不影响网页上的日志
            }
        }
    }

    private static void paintActionLog() {
        ThreadUtils.assertOnUiThread();
        View lock = sBanner;
        if (lock == null) return;
        LinearLayout box = lock.findViewWithTag("log");
        if (box == null) return;
        box.removeAllViews();
        Context context = box.getContext();
        for (String line : sActionLog) {
            TextView row = new TextView(context);
            row.setText(line);
            row.setTextColor(0xE6FFFFFF);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            row.setLineSpacing(dp(context, 2), 1f);
            row.setPadding(0, dp(context, 4), 0, dp(context, 4));
            box.addView(row);
        }
        ScrollView scroll = lock.findViewWithTag("logScroll");
        boolean open = sLogOpen && !sActionLog.isEmpty();
        if (scroll != null) {
            scroll.setVisibility(open ? View.VISIBLE : View.GONE);
            if (open) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
        TextView toggle = lock.findViewWithTag("logToggle");
        if (toggle != null) toggle.setText(open ? "▾" : "▸");
        TextView label = lock.findViewWithTag("label");
        if (label != null) {
            if (open) {
                label.setMaxLines(3);
                label.setText(sActionLog.get(sActionLog.size() - 1));
            } else {
                label.setMaxLines(1);
                label.setText(context.getString(R.string.lemurx_agent_working));
            }
        }
    }

    /** 网页上的步骤列表：点标题或箭头展开，再点收起。 */
    private static void toggleActionLog() {
        sLogOpen = !sLogOpen;
        paintActionLog();
    }

    private static String describeInput(JSONObject body) {
        Context context = ContextUtils.getApplicationContext();
        String kind = body.optString("type", "");
        switch (kind) {
            case "dialog":
                return context.getString(R.string.lemurx_agent_log_dialog_plain);
            case "click":
                String who = targetPhrase(body.opt("target"));
                if (!who.isEmpty()) return context.getString(R.string.lemurx_agent_log_click, who);
                if (body.has("x") && body.has("y")) {
                    return context.getString(R.string.lemurx_agent_log_click_at, placeName(body));
                }
                return context.getString(R.string.lemurx_agent_log_click_page);
            case "select":
                return context.getString(
                        R.string.lemurx_agent_log_select, clipLine(body.optString("text", ""), 48));
            case "text":
                if (secretTarget(body.opt("target"))) {
                    return context.getString(R.string.lemurx_agent_log_type_secret);
                }
                String typed = clipLine(body.optString("text", ""), 48);
                String field = targetPhrase(body.opt("target"));
                return field.isEmpty()
                        ? context.getString(R.string.lemurx_agent_log_type_here, typed)
                        : context.getString(R.string.lemurx_agent_log_type, field, typed);
            case "key":
                return context.getString(R.string.lemurx_agent_log_key, keyName(body.optString("key", "")));
            case "login":
            case "fill_secret":
                return context.getString(R.string.lemurx_agent_log_type_secret);
            case "scroll":
                return context.getString(body.optDouble("deltaY", 0) >= 0
                        ? R.string.lemurx_agent_log_scroll_down
                        : R.string.lemurx_agent_log_scroll_up);
            case "pinch":
                return context.getString(R.string.lemurx_agent_log_zoom);
            default:
                return context.getString(R.string.lemurx_agent_working);
        }
    }

    /** 对话框这一步带上原文，以及准备点的那个按钮。 */
    private static String describeDialog(int tabId, JSONObject body) {
        Context context = ContextUtils.getApplicationContext();
        JSONObject dialog = dialog(tabId);
        String message = dialog == null ? "" : dialog.optString("message", dialog.optString("title", ""));
        message = clipLine(message, 80);
        String choice = dialogChoice(dialog, body);
        if (!message.isEmpty() && !choice.isEmpty()) {
            return context.getString(R.string.lemurx_agent_log_dialog_choice, message, choice);
        }
        if (!message.isEmpty()) return context.getString(R.string.lemurx_agent_log_dialog, message);
        return context.getString(R.string.lemurx_agent_log_dialog_plain);
    }

    private static String dialogChoice(@Nullable JSONObject dialog, JSONObject body) {
        if (dialog == null) return "";
        org.json.JSONArray buttons = dialog.optJSONArray("buttons");
        if (buttons == null || buttons.length() == 0) return "";
        String action = body.optString("action", "accept");
        int index = "dismiss".equals(action) ? 1 : 0;
        if (index >= buttons.length()) index = 0;
        return clipLine(buttons.optString(index, ""), 24);
    }

    /** 页面标题；还没有标题时用网址，让人知道在哪一页。 */
    private static String pageName(@Nullable String title, @Nullable String url) {
        if (title != null && !title.trim().isEmpty()) return clipLine(title, 36);
        if (url != null && !url.isEmpty()) return briefUrl(url);
        return ContextUtils.getApplicationContext().getString(R.string.lemurx_agent_log_this_page);
    }

    /** 正文开头的一小段，空行折成一句话。 */
    private static String excerpt(@Nullable String text) {
        Context context = ContextUtils.getApplicationContext();
        if (text == null) return context.getString(R.string.lemurx_agent_log_excerpt_empty);
        String cleaned = text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) return context.getString(R.string.lemurx_agent_log_excerpt_empty);
        return clipLine(cleaned, 72);
    }

    /** 挑几个写了名字的控件，让「找到 N 处」能对上屏幕上的字。 */
    private static String exampleNames(LemurXPage.Snapshot snap) {
        Context context = ContextUtils.getApplicationContext();
        String join = context.getString(R.string.lemurx_agent_log_join);
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (LemurXPage.Element element : snap.elements) {
            String name = element.name.isEmpty() ? element.placeholder : element.name;
            if (name.isEmpty()) continue;
            if (count > 0) out.append(join);
            out.append(clipLine(name, 16));
            count++;
            if (count >= 4) break;
        }
        return out.toString();
    }

    /**
     * 把控件说成用户能对上屏幕的话：「登录」按钮、密码框。
     * 只有编号、没有名字时返回空，调用方改用画面位置来描述。
     */
    private static String targetPhrase(@Nullable Object target) {
        Context context = ContextUtils.getApplicationContext();
        if (secretTarget(target)) return context.getString(R.string.lemurx_agent_log_role_password);
        if (target instanceof JSONObject) {
            JSONObject object = (JSONObject) target;
            String name = firstText(object, "name", "text", "label", "placeholder");
            String role = roleWord(object.optString("role", ""));
            if (!name.isEmpty() && !role.isEmpty()) {
                return context.getString(R.string.lemurx_agent_log_named, clipLine(name, 28), role);
            }
            if (!name.isEmpty()) {
                return context.getString(R.string.lemurx_agent_log_quoted, clipLine(name, 28));
            }
            return role;
        }
        if (target instanceof String) {
            String text = ((String) target).trim();
            if (text.isEmpty() || text.matches("\\d+")) return "";
            return context.getString(R.string.lemurx_agent_log_quoted, clipLine(text, 28));
        }
        return "";
    }

    private static String firstText(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "").trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static boolean secretTarget(@Nullable Object target) {
        if (!(target instanceof JSONObject)) return false;
        JSONObject object = (JSONObject) target;
        String blob = (object.optString("name", "") + " " + object.optString("label", "") + " "
                + object.optString("placeholder", "") + " " + object.optString("role", "") + " "
                + object.optString("inputType", "") + " " + object.optString("type", ""))
                .toLowerCase(java.util.Locale.ROOT);
        return blob.contains("password") || blob.contains("passwd") || blob.contains("密码");
    }

    private static String roleWord(String role) {
        Context context = ContextUtils.getApplicationContext();
        switch (role.toLowerCase(java.util.Locale.ROOT)) {
            case "button":
            case "submit":
                return context.getString(R.string.lemurx_agent_log_role_button);
            case "link":
                return context.getString(R.string.lemurx_agent_log_role_link);
            case "textbox":
            case "searchbox":
            case "textarea":
            case "input":
                return context.getString(R.string.lemurx_agent_log_role_field);
            case "checkbox":
            case "radio":
            case "switch":
                return context.getString(R.string.lemurx_agent_log_role_check);
            case "menuitem":
            case "option":
                return context.getString(R.string.lemurx_agent_log_role_menu);
            case "image":
            case "img":
                return context.getString(R.string.lemurx_agent_log_role_image);
            default:
                return "";
        }
    }

    /** 坐标落在画面九宫格的哪一格，给没有名字的点击用。 */
    private static String placeName(JSONObject body) {
        double x = body.optDouble("x", CONSOLE_W / 2) / CONSOLE_W;
        double y = body.optDouble("y", CONSOLE_H / 2) / CONSOLE_H;
        int col = x < 0.34 ? 0 : x < 0.67 ? 1 : 2;
        int row = y < 0.34 ? 0 : y < 0.67 ? 1 : 2;
        int[][] ids = {
            {R.string.lemurx_agent_log_at_tl, R.string.lemurx_agent_log_at_tc,
                    R.string.lemurx_agent_log_at_tr},
            {R.string.lemurx_agent_log_at_ml, R.string.lemurx_agent_log_at_mc,
                    R.string.lemurx_agent_log_at_mr},
            {R.string.lemurx_agent_log_at_bl, R.string.lemurx_agent_log_at_bc,
                    R.string.lemurx_agent_log_at_br},
        };
        return ContextUtils.getApplicationContext().getString(ids[row][col]);
    }

    private static String keyName(String key) {
        Context context = ContextUtils.getApplicationContext();
        switch (key) {
            case "Enter":
                return context.getString(R.string.lemurx_agent_log_key_enter);
            case "Backspace":
                return context.getString(R.string.lemurx_agent_log_key_backspace);
            case "Tab":
                return context.getString(R.string.lemurx_agent_log_key_tab);
            case "Escape":
                return context.getString(R.string.lemurx_agent_log_key_esc);
            case "ArrowUp":
                return context.getString(R.string.lemurx_agent_log_key_up);
            case "ArrowDown":
                return context.getString(R.string.lemurx_agent_log_key_down);
            case "ArrowLeft":
                return context.getString(R.string.lemurx_agent_log_key_left);
            case "ArrowRight":
                return context.getString(R.string.lemurx_agent_log_key_right);
            case "Space":
            case " ":
                return context.getString(R.string.lemurx_agent_log_key_space);
            default:
                return clipLine(key, 24);
        }
    }

    private static String clipLine(String text, int max) {
        String cleaned = text.replace('\n', ' ').trim();
        if (cleaned.length() <= max) return cleaned;
        return cleaned.substring(0, max) + "…";
    }

    private static String briefUrl(String url) {
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return clipLine(url, 48);
            String path = uri.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) return host;
            return clipLine(host + path, 48);
        } catch (Exception e) {
            return clipLine(url, 48);
        }
    }

    private static void showBanner(int tabId, String text) {
        showBanner(tabId, text, false);
    }

    private static void showBanner(int tabId, String text, boolean yield) {
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
            sYielded = yield;
            TextView label = sBanner.findViewWithTag("label");
            if (label != null) label.setText(text);
            sBanner.setVisibility(View.VISIBLE);
            sBannerTab = tabId;
            sBanner.setTranslationZ(64f * activity.getResources().getDisplayMetrics().density);
            applyBannerChip();
            applyLockMode();
            paintActionLog();
            if (yield) {
                hideKeyboardNow();
            } else {
                forceHideSystemIme(webContentsForTab(tabId));
                sWatchdog.removeCallbacks(sImeWatch);
                sWatchdog.post(sImeWatch);
            }
            keepLockOnTop();
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
        sOverlay.setTranslationZ(72f * activity.getResources().getDisplayMetrics().density);
        content.addView(sOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        keepLockOnTop();
        return sOverlay;
    }

    private static void hideBanner() {
        ThreadUtils.runOnUiThread(() -> {
            sYielded = false;
            sWatchdog.removeCallbacks(sImeWatch);
            setImeSuppressed(null, false);
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

    /** 画中画里不显示接管胶囊。退出后再放回来。 */
    private static void setBannerChipVisible(boolean visible) {
        sHideBannerChip = !visible;
        if (ThreadUtils.runningOnUiThread()) applyBannerChip();
        else ThreadUtils.runOnUiThread(LemurXDeviceBridge::applyBannerChip);
    }

    private static void applyBannerChip() {
        ThreadUtils.assertOnUiThread();
        View lock = sBanner;
        if (!(lock instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) lock;
        if (group.getChildCount() == 0) return;
        View chip = group.getChildAt(0);
        if (chip == null) return;
        chip.setVisibility(sHideBannerChip ? View.GONE : View.VISIBLE);
    }

    /** 全屏透明锁：吃掉页面和底栏的触摸。底下是半透明步骤日志，只有「接管」能点。 */
    private static View buildLock(Context context) {
        FrameLayout lock = new FrameLayout(context) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                // 交给用户填写时不吞触摸，让网页和系统键盘能用。
                return !sYielded;
            }
        };
        lock.setClickable(true);
        lock.setFocusable(false);
        lock.setTranslationZ(64f * context.getResources().getDisplayMetrics().density);
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(0xB3132631);
        panelBg.setCornerRadius(dp(context, 18));
        panel.setBackground(panelBg);
        panel.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        panel.addView(buildBanner(context), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView scroll = new ScrollView(context);
        scroll.setTag("logScroll");
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout lines = new LinearLayout(context);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setTag("log");
        scroll.addView(lines, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 220));
        scrollLp.topMargin = dp(context, 6);
        scroll.setVisibility(View.GONE);
        panel.addView(scroll, scrollLp);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        int side = dp(context, 12);
        panelLp.setMargins(side, 0, side, side);
        lock.addView(panel, panelLp);
        return lock;
    }

    private static View buildBanner(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // 外层已经是半透明日志面板，这里只排「正在做的那一步」和接管按钮。
        row.setPadding(dp(context, 4), dp(context, 2), dp(context, 2), dp(context, 2));
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
        label.setOnClickListener(v -> toggleActionLog());
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView toggle = new TextView(context);
        toggle.setTag("logToggle");
        toggle.setText("▸");
        toggle.setTextColor(0xF0FFFFFF);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        toggle.setPadding(dp(context, 8), 0, dp(context, 4), 0);
        toggle.setOnClickListener(v -> toggleActionLog());
        row.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button take = new Button(context);
        take.setTag("take");
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
        take.setOnClickListener(v -> onLockChip());
        LinearLayout.LayoutParams takeLp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 32));
        takeLp.leftMargin = dp(context, 12);
        row.addView(take, takeLp);
        return row;
    }

    /** 接管胶囊：正在操作时断开；交给用户填写时点「继续」收回。 */
    private static void onLockChip() {
        if (sYielded) resumeDriving();
        else pauseByUser();
    }

    /**
     * 验证码等要用户自己出值：回到 Agent 对话里问，用户答完再继续。
     * 这一轮先停掉，发送按钮才能把验证码发出去。
     */
    private static void yieldToUser(int tabId, String reason) {
        sCaptchaHold = true;
        sHoldInTab = false;
        hideBanner();
        onUi(
                () -> {
                    Tab tab = findTab(tabId);
                    if (tab != null && tab.getUrl() != null) sCaptchaUrl = tab.getUrl().getSpec();
                    LemurXConsoleShell.returnToChat(tabId);
                    org.chromium.chrome.browser.lemurx.muse.MusePage.askCaptcha(tabId, reason);
                    return true;
                });
    }

    /** 用户在对话里发出了下一条：验证码这轮结束，Agent 可以接着操作这一页。 */
    public static void clearCaptcha() {
        sCaptchaHold = false;
        if (!TextUtils.isEmpty(sCaptchaUrl)) sCaptchaPassedUrl = sCaptchaUrl;
    }

    /** 还在等用户填验证码时，浏览和点击都停住。 */
    private static void requireNoCaptcha() throws SessionError {
        if (!sCaptchaHold) return;
        throw new SessionError(
                409,
                "CAPTCHA",
                "The page is showing a verification code. The user is typing it in the Agent chat. "
                        + "Wait for their next message, then enter that code on the page and continue. "
                        + "Do not guess the code.");
    }

    /**
     * 标题、地址或控件名字像验证码时，回到对话让用户填写。
     * 同一地址用户答过一次就不再打断，方便 Agent 把验证码填回去。
     */
    private static void considerCaptcha(
            int tabId,
            @Nullable String url,
            @Nullable String title,
            LemurXPage.@Nullable Snapshot snap)
            throws SessionError {
        if (sCaptchaHold) return;
        String pageUrl = url;
        String pageTitle = title;
        if (snap != null) {
            if (!TextUtils.isEmpty(snap.url)) pageUrl = snap.url;
            if (!TextUtils.isEmpty(snap.title)) pageTitle = snap.title;
        }
        if (pageUrl != null && pageUrl.equals(sCaptchaPassedUrl)) return;
        if (!looksLikeCaptcha(pageUrl, pageTitle, snap)) return;
        sCaptchaUrl = pageUrl == null ? "" : pageUrl;
        note(ContextUtils.getApplicationContext().getString(R.string.lemurx_agent_log_captcha));
        yieldToUser(tabId, null);
        throw new SessionError(
                409,
                "CAPTCHA",
                "The page is showing a verification code. Ask stopped so the user can type it in the Agent chat. "
                        + "Wait for their next message, then enter that code on the page and continue. "
                        + "Do not guess the code.");
    }

    private static boolean looksLikeCaptcha(
            @Nullable String url, @Nullable String title, LemurXPage.@Nullable Snapshot snap) {
        if (captchaToken(url, true) || captchaToken(title, false)) return true;
        if (snap == null) return false;
        for (LemurXPage.Element element : snap.elements) {
            if (captchaToken(element.name, false)
                    || captchaToken(element.placeholder, false)
                    || captchaToken(element.url, true)) {
                return true;
            }
        }
        return false;
    }

    /** 短标题和控件名才算，避免正文里随口出现这两个字就把整页打断。地址不限长度。 */
    private static boolean captchaToken(@Nullable String value, boolean url) {
        if (value == null || value.isEmpty()) return false;
        if (!url && value.length() > 64) return false;
        String text = value.toLowerCase(Locale.ROOT);
        return text.contains("captcha")
                || text.contains("recaptcha")
                || text.contains("hcaptcha")
                || text.contains("turnstile")
                || text.contains("验证码")
                || text.contains("人机验证")
                || text.contains("verify you are human")
                || text.contains("i'm not a robot")
                || text.contains("im not a robot")
                || text.contains("我不是机器人")
                || text.contains("确认您是真人");
    }

    /** 用户填完了：收回页面，重新按住系统键盘。 */
    private static void resumeDriving() {
        ThreadUtils.assertOnUiThread();
        if (!sYielded && isDriving()) return;
        sYielded = false;
        applyLockMode();
        View lock = sBanner;
        if (lock != null) {
            TextView label = lock.findViewWithTag("label");
            if (label != null) {
                label.setText(
                        ContextUtils.getApplicationContext()
                                .getString(R.string.lemurx_agent_working));
            }
        }
        forceHideSystemIme(webContentsForTab(sBannerTab));
        sWatchdog.removeCallbacks(sImeWatch);
        sWatchdog.post(sImeWatch);
        keepLockOnTop();
    }

    /** 全屏锁 vs 只留胶囊。UI 线程。 */
    private static void applyLockMode() {
        ThreadUtils.assertOnUiThread();
        View lock = sBanner;
        if (lock == null) return;
        ViewGroup.LayoutParams raw = lock.getLayoutParams();
        if (raw instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            if (sYielded) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.gravity = Gravity.CENTER;
                lock.setClickable(false);
            } else {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.gravity = Gravity.NO_GRAVITY;
                lock.setClickable(true);
            }
            lock.setLayoutParams(lp);
        }
        Button take = lock.findViewWithTag("take");
        if (take != null) {
            take.setText(sYielded ? R.string.lemurx_muse_resume : R.string.lemurx_muse_take_over);
        }
        keepLockOnTop();
    }

    /**
     * 接管：停掉当前操作、放开页面。长连接不断——下一句对话来了 Agent 还要能 browser_look。
     * 只有测试包远程关停才把 {@link #sPausedByUser} 拉起来，那才会真的不再连。
     */
    private static void pauseByUser() {
        ThreadUtils.assertOnUiThread();
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
