// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.agent;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputConnection;

import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.Nullable;
import org.chromium.content_public.browser.ImeAdapter;
import org.chromium.content_public.browser.WebContents;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 像真人一样操作屏幕：手指触摸走 Android 的触摸事件管线，文字走输入法通道。
 *
 * <p>为什么不走 DevTools 的 Input.dispatchMouseEvent / Input.insertText：它们绕过合成器和输入法，
 * 页面收到的事件序列（没有 pointer/touch 序列、没有 composition 事件、时间戳整齐划一）和真人完全
 * 不同，是反机器人系统最常用的判据。这里的每一次点击都是一条真实的 ACTION_DOWN → MOVE… → UP
 * 序列（带压力、接触面积、微小抖动、落指停顿），每一个字都是输入法会发出的
 * setComposingText / commitText，页面侧和 Gboard 打字没有区别。
 *
 * <p>节奏模型（够用即可，不追求学术精确）：
 *
 * <ul>
 *   <li>点击：落点在目标周围 ±几 px 的高斯分布；按住 60–140 ms；按住期间 1–3 次亚像素漂移。
 *   <li>滑动：二次贝塞尔曲线（控制点侧偏 3–8%），缓入缓出，时长按距离取 300–700 ms，末端减速
 *       到近零以免甩出惯性滚动；落指先停 30–80 ms 再动。
 *   <li>打字：每个字 70–180 ms，空格 / 标点后更久，偶尔 400–900 ms 的"想一下"；英文按词
 *       组合（composing 下划线）在分隔符处提交，中文按 2–4 字一段提交——和主流输入法一致。
 * </ul>
 *
 * <p>触摸在 UI 线程上按时间表异步投递（不阻塞 UI 线程）；打字在调用线程睡眠节拍，
 * InputConnection 自己会把每一步投到 UI 线程。两者都接受一个 {@code abort} 判断：用户一碰屏幕立刻停手（见
 * {@link LemurXDeviceBridge#onUserTouch}）。
 */
final class LemurXHumanInput {
    private static final String TAG = "LemurXAgent";

    /** 这只"手"的固有偏置：拇指落点略偏一侧、手速快慢；进程内稳定，像同一个人。 */
    private static final Random RNG = new Random();
    private static final float BIAS_X = (float) (RNG.nextGaussian() * 1.5);
    private static final float BIAS_Y = (float) (2 + RNG.nextGaussian() * 1.5);
    private static final double SPEED = 0.85 + RNG.nextDouble() * 0.3;

    private static final Map<String, int[]> KEYS = new HashMap<>();

    static {
        // name → {keyCode, metaState}
        KEYS.put("Enter", new int[] {KeyEvent.KEYCODE_ENTER, 0});
        KEYS.put("Tab", new int[] {KeyEvent.KEYCODE_TAB, 0});
        KEYS.put("Escape", new int[] {KeyEvent.KEYCODE_ESCAPE, 0});
        KEYS.put("Backspace", new int[] {KeyEvent.KEYCODE_DEL, 0});
        KEYS.put("Delete", new int[] {KeyEvent.KEYCODE_FORWARD_DEL, 0});
        KEYS.put("ArrowUp", new int[] {KeyEvent.KEYCODE_DPAD_UP, 0});
        KEYS.put("ArrowDown", new int[] {KeyEvent.KEYCODE_DPAD_DOWN, 0});
        KEYS.put("ArrowLeft", new int[] {KeyEvent.KEYCODE_DPAD_LEFT, 0});
        KEYS.put("ArrowRight", new int[] {KeyEvent.KEYCODE_DPAD_RIGHT, 0});
        KEYS.put("Home", new int[] {KeyEvent.KEYCODE_MOVE_HOME, 0});
        KEYS.put("End", new int[] {KeyEvent.KEYCODE_MOVE_END, 0});
        KEYS.put("PageUp", new int[] {KeyEvent.KEYCODE_PAGE_UP, 0});
        KEYS.put("PageDown", new int[] {KeyEvent.KEYCODE_PAGE_DOWN, 0});
        KEYS.put("Control+a",
                new int[] {KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON});
        KEYS.put("Meta+a",
                new int[] {KeyEvent.KEYCODE_A, KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON});
        KEYS.put("Shift+Tab", new int[] {
                KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON});
    }

    private LemurXHumanInput() {}

    static boolean isKnownKey(String name) {
        return KEYS.containsKey(name);
    }

    // ---------------------------------------------------------------- 触摸

    /** 真实触摸屏的 deviceId（没有就 -1），让事件的来源字段和手指一致。 */
    private static int touchDeviceId() {
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice d = InputDevice.getDevice(id);
                if (d != null && (d.getSources() & InputDevice.SOURCE_TOUCHSCREEN)
                        == InputDevice.SOURCE_TOUCHSCREEN) {
                    return id;
                }
            }
        } catch (Exception e) {
            // 退回 -1
        }
        return -1;
    }

    private static final class Finger {
        final View view;
        final long downTime;
        final int deviceId = touchDeviceId();
        final float pressure;
        final float size;
        final MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
        final MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        boolean ok;

        Finger(View view, long downTime) {
            this.view = view;
            this.downTime = downTime;
            pressure = (float) clamp(0.45 + RNG.nextGaussian() * 0.1, 0.2, 0.9);
            size = (float) clamp(0.08 + RNG.nextGaussian() * 0.02, 0.03, 0.2);
            props[0].id = 0;
            props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        }

        void send(int action, float x, float y, long time) {
            MotionEvent.PointerCoords c = coords[0];
            c.clear();
            c.x = x;
            c.y = y;
            // 压力 / 接触面积随时间微微起伏（真手指不是恒定的）。
            c.pressure = (float) clamp(pressure + RNG.nextGaussian() * 0.02, 0.1, 1);
            c.size = (float) clamp(size + RNG.nextGaussian() * 0.005, 0.02, 0.3);
            float major = 40 + size * 300;
            c.touchMajor = major;
            c.touchMinor = major * 0.8f;
            c.orientation = 0.3f;
            MotionEvent event = MotionEvent.obtain(downTime, time, action, 1, props, coords, 0, 0,
                    1f, 1f, deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            ok = view.dispatchTouchEvent(event) || ok;
            event.recycle();
        }
    }

    /** 一步触摸：相对手势开始的时间偏移 + 动作 + 坐标。整条轨迹在开始前规划好。 */
    private static final class Step {
        final int at;
        final int action;
        final float x;
        final float y;

        Step(int at, int action, float x, float y) {
            this.at = at;
            this.action = action;
            this.x = x;
            this.y = y;
        }
    }

    /**
     * 在 UI 线程上按时间表播放一条轨迹，不阻塞 UI 线程（否则渲染进程的触摸 ack、手势识别都会被
     * 卡住，事件序列反而不像真人）。完成后在 UI 线程回调 {@code done}。
     */
    /** 落指前先把目标标出来这么久，用户看得到手指"伸过去"。 */
    private static final int AIM_MS = 260;

    private static void play(View view, List<Step> steps, @Nullable LemurXTouchOverlay overlay,
            BooleanSupplier abort, Callback done) {
        ThreadUtils.assertOnUiThread();
        int lead = overlay != null ? AIM_MS : 0;
        Finger f = new Finger(view, SystemClock.uptimeMillis() + lead);
        if (overlay != null) overlay.aim(view, steps.get(0).x, steps.get(0).y, lead);
        Handler h = new Handler(Looper.getMainLooper());
        int[] index = {0};
        float[] last = {steps.get(0).x, steps.get(0).y};
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (index[0] >= steps.size()) {
                    done.onResult(f.ok);
                    return;
                }
                Step s = steps.get(index[0]);
                long now = SystemClock.uptimeMillis();
                if (s.action != MotionEvent.ACTION_DOWN && abort.getAsBoolean()) {
                    f.send(MotionEvent.ACTION_CANCEL, last[0], last[1], now);
                    if (overlay != null) overlay.lift();
                    done.onResult(false);
                    return;
                }
                f.send(s.action, s.x, s.y, now);
                last[0] = s.x;
                last[1] = s.y;
                if (overlay != null) {
                    if (s.action == MotionEvent.ACTION_DOWN) overlay.touch(view, s.x, s.y);
                    else if (s.action == MotionEvent.ACTION_MOVE) overlay.move(view, s.x, s.y);
                    else overlay.lift();
                }
                index[0]++;
                if (index[0] < steps.size()) {
                    long next = f.downTime + steps.get(index[0]).at;
                    h.postDelayed(this, Math.max(0, next - SystemClock.uptimeMillis()));
                } else {
                    done.onResult(f.ok);
                }
            }
        };
        if (lead > 0) h.postDelayed(tick, lead);
        else tick.run();
    }

    /** 手势完成回调（UI 线程）。 */
    interface Callback {
        void onResult(boolean ok);
    }

    /** 真人点击：落点高斯抖动，按住 60–140 ms 并微微漂移。UI 线程发起，异步完成。 */
    static void tap(View view, float x, float y, @Nullable LemurXTouchOverlay overlay,
            BooleanSupplier abort, Callback done) {
        x += BIAS_X + (float) (RNG.nextGaussian() * 2.5);
        y += BIAS_Y + (float) (RNG.nextGaussian() * 2.5);
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(0, MotionEvent.ACTION_DOWN, x, y));
        int hold = (int) ((60 + RNG.nextInt(80)) * SPEED);
        int drifts = 1 + RNG.nextInt(3);
        for (int i = 1; i <= drifts; i++) {
            x += (float) (RNG.nextGaussian() * 0.6);
            y += (float) (RNG.nextGaussian() * 0.6);
            steps.add(new Step(hold * i / (drifts + 1), MotionEvent.ACTION_MOVE, x, y));
        }
        steps.add(new Step(hold, MotionEvent.ACTION_UP, x, y));
        play(view, steps, overlay, abort, done);
    }

    /** 真人长按：按住 {@code holdMs}（至少 300）。 */
    static void longPress(View view, float x, float y, int holdMs,
            @Nullable LemurXTouchOverlay overlay, BooleanSupplier abort, Callback done) {
        x += BIAS_X + (float) (RNG.nextGaussian() * 2);
        y += BIAS_Y + (float) (RNG.nextGaussian() * 2);
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(0, MotionEvent.ACTION_DOWN, x, y));
        int hold = Math.max(300, holdMs);
        for (int t = 40 + RNG.nextInt(30); t < hold; t += 40 + RNG.nextInt(30)) {
            x += (float) (RNG.nextGaussian() * 0.4);
            y += (float) (RNG.nextGaussian() * 0.4);
            steps.add(new Step(t, MotionEvent.ACTION_MOVE, x, y));
        }
        steps.add(new Step(hold, MotionEvent.ACTION_UP, x, y));
        play(view, steps, overlay, abort, done);
    }

    /**
     * 真人滑动：贝塞尔轨迹 + 缓入缓出，{@code settle} 为真时末端减速到静止（滚动用，不甩惯性），
     * 为假时带速度抬手（翻页 / 轻扫）。UI 线程发起，300–800 ms 后异步完成。
     */
    static void swipe(View view, float x1, float y1, float x2, float y2, boolean settle,
            @Nullable LemurXTouchOverlay overlay, BooleanSupplier abort, Callback done) {
        x1 += BIAS_X + (float) (RNG.nextGaussian() * 3);
        y1 += BIAS_Y + (float) (RNG.nextGaussian() * 3);
        x2 += (float) (RNG.nextGaussian() * 4);
        y2 += (float) (RNG.nextGaussian() * 4);
        double dist = Math.hypot(x2 - x1, y2 - y1);
        // Fitts 式时长：短距离也要 300 ms，长距离 700 ms 封顶。
        int duration = (int) (clamp(260 + dist * 0.35, 300, 700) * SPEED);
        // 控制点：中点向法线方向偏 3–8%，左右随机，轨迹是弧而不是直线。
        double nx = -(y2 - y1) / Math.max(1, dist);
        double ny = (x2 - x1) / Math.max(1, dist);
        double bend = dist * (0.03 + RNG.nextDouble() * 0.05) * (RNG.nextBoolean() ? 1 : -1);
        double cx = (x1 + x2) / 2 + nx * bend;
        double cy = (y1 + y2) / 2 + ny * bend;

        List<Step> steps = new ArrayList<>();
        steps.add(new Step(0, MotionEvent.ACTION_DOWN, x1, y1));
        int start = 30 + RNG.nextInt(50); // 落指停顿
        float lx = x1;
        float ly = y1;
        for (int t = 0; ; t += 8 + RNG.nextInt(9)) { // 60–120 Hz 的采样间隔
            double u = Math.min(1, t / (double) duration);
            double e = settle ? easeInOut(u) : easeIn(u);
            float x = (float) ((1 - e) * (1 - e) * x1 + 2 * (1 - e) * e * cx + e * e * x2);
            float y = (float) ((1 - e) * (1 - e) * y1 + 2 * (1 - e) * e * cy + e * e * y2);
            x += (float) (RNG.nextGaussian() * 0.5);
            y += (float) (RNG.nextGaussian() * 0.5);
            steps.add(new Step(start + t, MotionEvent.ACTION_MOVE, x, y));
            lx = x;
            ly = y;
            if (u >= 1) break;
        }
        int end = start + duration;
        if (settle) {
            // 停住一拍再抬，速度归零 → 不触发 fling。
            end += 60 + RNG.nextInt(60);
            steps.add(new Step(end, MotionEvent.ACTION_MOVE, lx, ly));
        }
        steps.add(new Step(end + 5, MotionEvent.ACTION_UP, lx, ly));
        play(view, steps, overlay, abort, done);
    }

    // ---------------------------------------------------------------- 双指缩放

    /**
     * 真人双指缩放：两根手指以 ({@code cx},{@code cy}) 为中心、沿一条略斜的轴，从相距
     * {@code startSpan} 张开 / 合拢到 {@code endSpan}（物理像素）。两指不是同时落下、也不是同时
     * 抬起（相差 20–60 ms），轨迹缓入缓出带抖动。可视化层只画第一根手指。UI 线程发起，异步完成。
     */
    static void pinch(View view, float cx, float cy, float startSpan, float endSpan,
            @Nullable LemurXTouchOverlay overlay, BooleanSupplier abort, Callback done) {
        ThreadUtils.assertOnUiThread();
        cx += BIAS_X + (float) (RNG.nextGaussian() * 3);
        cy += BIAS_Y + (float) (RNG.nextGaussian() * 3);
        // 轴向：大致竖直略带倾斜（拇指 + 食指），左右随机。
        double angle = Math.PI / 2 + (RNG.nextDouble() - 0.5) * 0.9;
        double ux = Math.cos(angle);
        double uy = Math.sin(angle);
        // 真手的张合不以几何中心对称：拇指动得少一些。
        double thumbShare = 0.38 + RNG.nextDouble() * 0.1;
        int duration = (int) (clamp(380 + Math.abs(endSpan - startSpan) * 0.45, 420, 900) * SPEED);
        int lead = overlay != null ? AIM_MS : 0;
        long downTime = SystemClock.uptimeMillis() + lead;
        int secondDown = 20 + RNG.nextInt(40);
        // 两指都落下后先停一拍再张合：真手如此，页面那边也需要这点时间把 touchstart 处理完——
        // 刚导航完的页面第一个触摸序列的应答慢，没应答前的缩放量会被浏览器丢掉。
        int hold = 180 + RNG.nextInt(120);
        int moveStart = secondDown + hold;
        int firstUp = duration + moveStart + 40 + RNG.nextInt(50);
        int secondUp = firstUp + 20 + RNG.nextInt(40);
        Log.i(TAG, "pinch at %.0f,%.0f span %.0f->%.0f angle=%.2f thumb=%.2f dur=%d hold=%d", cx,
                cy, startSpan, endSpan, angle, thumbShare, duration, hold);

        final int deviceId = touchDeviceId();
        final MotionEvent.PointerProperties[] props = {
            new MotionEvent.PointerProperties(), new MotionEvent.PointerProperties()
        };
        final MotionEvent.PointerCoords[] coords = {
            new MotionEvent.PointerCoords(), new MotionEvent.PointerCoords()
        };
        for (int i = 0; i < 2; i++) {
            props[i].id = i;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
        }
        final float[] pressure = {
            (float) clamp(0.45 + RNG.nextGaussian() * 0.1, 0.2, 0.9),
            (float) clamp(0.40 + RNG.nextGaussian() * 0.1, 0.2, 0.9)
        };
        final float[] pos = new float[4]; // x0 y0 x1 y1
        final boolean[] ok = {false};
        final boolean[] downed = {false, false};
        final float fcx = cx;
        final float fcy = cy;

        if (overlay != null) {
            float x0 = (float) (cx - ux * startSpan * thumbShare);
            float y0 = (float) (cy - uy * startSpan * thumbShare);
            overlay.aim(view, x0, y0, lead);
        }
        Handler h = new Handler(Looper.getMainLooper());
        Runnable tick = new Runnable() {
            int t = 0;
            boolean finished;

            void place(double u) {
                double e = easeInOut(u);
                double span = startSpan + (endSpan - startSpan) * e;
                pos[0] = (float) (fcx - ux * span * thumbShare + RNG.nextGaussian() * 0.5);
                pos[1] = (float) (fcy - uy * span * thumbShare + RNG.nextGaussian() * 0.5);
                pos[2] = (float) (fcx + ux * span * (1 - thumbShare) + RNG.nextGaussian() * 0.5);
                pos[3] = (float) (fcy + uy * span * (1 - thumbShare) + RNG.nextGaussian() * 0.5);
            }

            void send(int action, int count, long now) {
                for (int i = 0; i < 2; i++) {
                    MotionEvent.PointerCoords c = coords[i];
                    c.clear();
                    c.x = pos[i * 2];
                    c.y = pos[i * 2 + 1];
                    c.pressure = (float) clamp(pressure[i] + RNG.nextGaussian() * 0.02, 0.1, 1);
                    c.size = 0.08f;
                    c.touchMajor = 64;
                    c.touchMinor = 52;
                    c.orientation = 0.3f;
                }
                MotionEvent ev = MotionEvent.obtain(downTime, now, action, count, props, coords,
                        0, 0, 1f, 1f, deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                ok[0] = view.dispatchTouchEvent(ev) || ok[0];
                ev.recycle();
            }

            void finish(boolean result) {
                if (finished) return;
                finished = true;
                if (overlay != null) overlay.lift();
                done.onResult(result);
            }

            @Override
            public void run() {
                long now = SystemClock.uptimeMillis();
                if (abort.getAsBoolean() && downed[0]) {
                    // 两指都算进 CANCEL；pointerCount 按还按着的手指数。
                    send(MotionEvent.ACTION_CANCEL, downed[1] ? 2 : 1, now);
                    finish(false);
                    return;
                }
                if (!downed[0]) {
                    place(0);
                    send(MotionEvent.ACTION_DOWN, 1, now);
                    downed[0] = true;
                    if (overlay != null) overlay.touch(view, pos[0], pos[1]);
                    t = secondDown;
                    h.postDelayed(this, secondDown);
                    return;
                }
                if (!downed[1]) {
                    place(0);
                    send(MotionEvent.ACTION_POINTER_DOWN
                            | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, now);
                    downed[1] = true;
                    t += 8 + RNG.nextInt(9);
                    h.postDelayed(this, Math.max(0, downTime + t - now));
                    return;
                }
                int moveT = t - moveStart;
                if (moveT <= duration) {
                    place(Math.max(0, Math.min(1, moveT / (double) duration)));
                    send(MotionEvent.ACTION_MOVE, 2, now);
                    if (overlay != null) overlay.move(view, pos[0], pos[1]);
                    t += 8 + RNG.nextInt(9);
                    h.postDelayed(this, Math.max(0, downTime + t - now));
                    return;
                }
                if (t < firstUp) {
                    // 停住一拍再抬，速度归零。
                    send(MotionEvent.ACTION_MOVE, 2, now);
                    t = firstUp;
                    h.postDelayed(this, Math.max(0, downTime + t - now));
                    return;
                }
                if (downed[1] && t < secondUp) {
                    send(MotionEvent.ACTION_POINTER_UP
                            | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, now);
                    downed[1] = false;
                    t = secondUp;
                    h.postDelayed(this, Math.max(0, downTime + t - now));
                    return;
                }
                send(MotionEvent.ACTION_UP, 1, now);
                downed[0] = false;
                finish(ok[0]);
            }
        };
        if (lead > 0) h.postDelayed(tick, lead);
        else tick.run();
    }

    // ---------------------------------------------------------------- 键盘

    /** 当前有没有可编辑节点聚焦（即输入法通道可用）。任意线程。 */
    static boolean hasEditableFocus(@Nullable WebContents wc) {
        if (wc == null) return false;
        ImeAdapter ime = ImeAdapter.fromWebContents(wc);
        return ime != null && ime.getActiveInputConnection() != null;
    }

    /**
     * 屏幕上的键盘可视化（{@link LemurXAgentKeyboard}）。每个方法都在桥接线程调用，可以阻塞着
     * 按节拍睡眠；文字提交仍由 {@link #typeText} 自己走 InputConnection。
     */
    interface Keys {
        /** 把一个字素"按出来"（含换层 / Shift / 候选词）。 */
        void press(String grapheme);

        /** 按一个功能键（Enter / Backspace / …）。 */
        void pressKey(String name);

        /** 回显已经打出的文字。 */
        void echo(String text);
    }

    /**
     * 像输入法一样把文字打进聚焦的输入框。调用线程会按节拍睡眠（约每字 0.1–0.2 s）。
     * {@code keys} 非空时每个字先在屏幕键盘上按出来，再提交给页面。
     * 返回 false 表示没有聚焦的输入框（agent 应先点击它）。
     */
    static boolean typeText(@Nullable WebContents wc, String text, BooleanSupplier abort,
            @Nullable Keys keys) {
        if (wc == null) return false;
        ImeAdapter ime = ImeAdapter.fromWebContents(wc);
        InputConnection ic = ime == null ? null : ime.getActiveInputConnection();
        if (ic == null) return false;

        List<String> clusters = graphemes(text);
        StringBuilder composing = new StringBuilder();
        StringBuilder typed = new StringBuilder(); // 已经打出去的全部文字（给屏幕回显）
        boolean cjkRun = false;
        int cjkCount = 0;
        int cjkChunk = 2 + RNG.nextInt(3);
        for (int i = 0; i < clusters.size(); i++) {
            if (abort.getAsBoolean()) {
                if (composing.length() > 0) onIme(ic, ic::finishComposingText);
                return false;
            }
            String g = clusters.get(i);
            int cp = g.codePointAt(0);
            if (g.equals("\n")) {
                if (composing.length() > 0) {
                    commit(ic, composing.toString());
                    composing.setLength(0);
                }
                sleep(jitter(180, 80));
                if (keys != null) keys.press("\n");
                commit(ic, "\n"); // Chromium 把它转成软键盘的 Enter 按键
                typed.append('⏎');
                if (keys != null) keys.echo(typed.toString());
                sleep(jitter(220, 100));
                cjkRun = false;
                cjkCount = 0;
                continue;
            }
            boolean cjk = isCjk(cp);
            boolean separator = !cjk && !Character.isLetterOrDigit(cp) && cp != '\'' && cp != '-';
            if (separator) {
                // 词结束：提交词 + 分隔符（Gboard 在空格 / 标点时提交）。
                if (keys != null) keys.press(g);
                composing.append(g);
                commit(ic, composing.toString());
                typed.append(composing);
                composing.setLength(0);
                if (keys != null) keys.echo(typed.toString());
                sleep(jitter(cp == ' ' ? 140 : 200, 70));
                cjkRun = false;
                cjkCount = 0;
                continue;
            }
            if (cjk != cjkRun && composing.length() > 0) {
                commit(ic, composing.toString());
                typed.append(composing);
                composing.setLength(0);
                cjkCount = 0;
            }
            cjkRun = cjk;
            if (keys != null) keys.press(g);
            composing.append(g);
            compose(ic, composing.toString());
            if (keys != null) keys.echo(typed.toString() + composing);
            sleep(jitter(cjk ? 140 : 70, 35));
            if (cjk && ++cjkCount >= cjkChunk) {
                // 拼音输入法：几个字一段候选确认。
                commit(ic, composing.toString());
                typed.append(composing);
                composing.setLength(0);
                cjkCount = 0;
                cjkChunk = 2 + RNG.nextInt(3);
                sleep(jitter(220, 90));
            }
            if (RNG.nextInt(100) < 3) sleep(400 + RNG.nextInt(500)); // 想一下
        }
        if (composing.length() > 0) {
            commit(ic, composing.toString());
            typed.append(composing);
        }
        if (keys != null) keys.echo(typed.toString());
        return true;
    }

    /**
     * 按一个键：有输入焦点时走输入法通道（软键盘按键），否则把 KeyEvent 投给内容视图（实体键盘
     * 路径，页面照样收到 keydown/keyup）。任意线程；视图投递会切到 UI 线程。
     */
    static boolean pressKey(@Nullable WebContents wc, @Nullable View view, String name) {
        int[] k = KEYS.get(name);
        if (k == null) return false;
        long t = SystemClock.uptimeMillis();
        int flags = KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE;
        KeyEvent down = new KeyEvent(t, t, KeyEvent.ACTION_DOWN, k[0], 0, k[1],
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags);
        KeyEvent up = new KeyEvent(t, t + 40 + RNG.nextInt(50), KeyEvent.ACTION_UP, k[0], 0, k[1],
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags);
        ImeAdapter ime = wc == null ? null : ImeAdapter.fromWebContents(wc);
        InputConnection ic = ime == null ? null : ime.getActiveInputConnection();
        if (ic != null && k[1] == 0) {
            onIme(ic, () -> ic.sendKeyEvent(down));
            sleep(40 + RNG.nextInt(50));
            onIme(ic, () -> ic.sendKeyEvent(up));
            return true;
        }
        if (view == null) return false;
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] ok = new boolean[1];
        ThreadUtils.runOnUiThread(() -> {
            try {
                ok[0] = view.dispatchKeyEvent(down);
                ok[0] = view.dispatchKeyEvent(up) || ok[0];
            } catch (Exception e) {
                Log.i(TAG, "key: %s", e.toString());
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok[0];
    }

    // ---------------------------------------------------------------- 输入法线程

    /**
     * Chromium 的 ThreadedInputConnection 要求在它自己的输入法线程上被调用（例如
     * commitText("\n") 走 beginBatchEdit 会断言线程），所以所有 InputConnection 调用都投到
     * {@link InputConnection#getHandler()} 那条线程；同一 Handler 顺序执行，节拍不乱。
     */
    private static void onIme(InputConnection ic, Runnable r) {
        Handler h = ic.getHandler();
        if (h == null || h.getLooper() == Looper.myLooper()) {
            r.run();
        } else {
            h.post(r);
        }
    }

    private static void commit(InputConnection ic, String text) {
        onIme(ic, () -> ic.commitText(text, 1));
    }

    private static void compose(InputConnection ic, String text) {
        onIme(ic, () -> ic.setComposingText(text, 1));
    }

    // ---------------------------------------------------------------- 工具

    private static List<String> graphemes(String text) {
        List<String> out = new ArrayList<>();
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(text);
        int start = it.first();
        for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            out.add(text.substring(start, end));
        }
        return out;
    }

    private static boolean isCjk(int cp) {
        Character.UnicodeScript s = Character.UnicodeScript.of(cp);
        return s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA
                || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL;
    }

    private static int jitter(int mean, int sd) {
        return (int) clamp((mean + RNG.nextGaussian() * sd) * SPEED, 30, 1200);
    }

    private static double easeInOut(double u) {
        return u < 0.5 ? 4 * u * u * u : 1 - Math.pow(-2 * u + 2, 3) / 2;
    }

    private static double easeIn(double u) {
        return u * u;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
