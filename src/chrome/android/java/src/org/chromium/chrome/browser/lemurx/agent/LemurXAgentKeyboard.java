// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.agent;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.lemurx.muse.MusePage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Agent 自己的软键盘。
 *
 * <p>系统输入法（Gboard 等）是另一个进程的窗口，永远压在应用窗口上面：我们既不能把触摸事件
 * 投进去，也不能在它上面画手指。所以 agent 打字时把系统输入法收起来，在应用窗口里铺一块
 * 长得像手机键盘的面板，agent 的手指（{@link LemurXTouchOverlay}）一键一键地按在上面——
 * 按下的键高亮、弹出预览，和真人打字看到的一样。文字本身仍然通过 Chromium 的
 * InputConnection 走输入法通道提交，网页看到的和系统输入法打字完全一致。
 *
 * <p>面板不接收触摸：用户一碰屏幕就是接管，面板随即收起，用户再点输入框系统输入法照常弹出。
 *
 * <p>线程：绘制与状态在 UI 线程；{@link #press} / {@link #pressKey} / {@link #echo} 由桥接
 * 线程调用并阻塞着按节拍睡眠。
 */
final class LemurXAgentKeyboard extends View implements LemurXHumanInput.Keys {
    private static final int ACCENT = LemurXTouchOverlay.ACCENT;
    private static final Random RNG = new Random();

    private static final String SHIFT = "⇧";
    private static final String BACKSPACE = "⌫";
    private static final String ENTER = "⏎";
    private static final String SPACE = "␣";
    private static final String TO_SYMBOLS = "?123";
    private static final String TO_LETTERS = "ABC";
    private static final String CANDIDATE = "\u0000cand";

    private static final String[][] LETTERS = {
        {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
        {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
        {SHIFT, "z", "x", "c", "v", "b", "n", "m", BACKSPACE},
        {TO_SYMBOLS, ",", SPACE, ".", ENTER},
    };
    private static final String[][] SYMBOLS = {
        {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
        {"@", "#", "$", "_", "&", "-", "+", "(", ")", "/"},
        {"=\\<", "*", "\"", "'", ":", ";", "!", "?", BACKSPACE},
        {TO_LETTERS, ",", SPACE, ".", ENTER},
    };

    private static final class Key {
        final String label;
        final RectF rect = new RectF();

        Key(String label) {
            this.label = label;
        }

        boolean special() {
            return label.length() > 1 || label.equals(SPACE);
        }
    }

    private final Paint mBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCap = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCapSpecial = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCapPressed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelSmall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPreview = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mChip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDensity;
    private final int mBottomInset;
    private final int mInk;
    private final List<Key> mKeys = new ArrayList<>();
    private final RectF mChipRect = new RectF();

    private boolean mSymbols;
    private boolean mShift;
    private @Nullable String mPressed;
    private long mPressedAt;
    private @Nullable String mCandidate;
    private @Nullable String mEcho;

    LemurXAgentKeyboard(Context context, int bottomInset) {
        super(context);
        mDensity = context.getResources().getDisplayMetrics().density;
        mBottomInset = bottomInset;
        boolean night = MusePage.night(context);
        mInk = night ? 0xFFE7EEF2 : 0xFF1F1F1F;
        mBg.setColor(night ? 0xFF10181C : 0xFFECEFF1);
        mCap.setColor(night ? 0xFF243038 : 0xFFFFFFFF);
        mCapSpecial.setColor(night ? 0xFF1A242A : 0xFFCFD5DA);
        mCapPressed.setColor(ACCENT);
        mLabel.setColor(mInk);
        mLabel.setTextSize(22 * mDensity);
        mLabel.setTextAlign(Paint.Align.CENTER);
        mLabelSmall.setColor(mInk);
        mLabelSmall.setTextSize(14 * mDensity);
        mLabelSmall.setTextAlign(Paint.Align.CENTER);
        mLabelSmall.setTypeface(Typeface.DEFAULT_BOLD);
        mPreview.setColor(mInk);
        mPreview.setTextSize(30 * mDensity);
        mPreview.setTextAlign(Paint.Align.CENTER);
        mPreview.setTypeface(Typeface.DEFAULT_BOLD);
        mStrip.setColor(night ? 0xFF9AA6B0 : 0xFF5F6368);
        mStrip.setTextSize(15 * mDensity);
        mChip.setColor(ACCENT);
        mChip.setStyle(Paint.Style.STROKE);
        mChip.setStrokeWidth(2 * mDensity);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
        rebuild();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    // ---------------------------------------------------------------- 尺寸

    private float dp(float v) {
        return v * mDensity;
    }

    private float stripH() {
        return dp(46);
    }

    private float rowH() {
        return dp(54);
    }

    private float padX() {
        return dp(4);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = (int) (stripH() + rowH() * 4 + dp(8) + mBottomInset);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        rebuild();
    }

    /** 根据当前层重新排键位。 */
    private void rebuild() {
        mKeys.clear();
        String[][] layer = mSymbols ? SYMBOLS : LETTERS;
        float w = getWidth();
        if (w <= 0) return;
        float unit = (w - padX() * 2) / 10f;
        float gapX = dp(3);
        float gapY = dp(6);
        for (int r = 0; r < layer.length; r++) {
            String[] row = layer[r];
            float total = 0;
            for (String k : row) total += weight(k);
            float x = padX() + (10 - total) * unit / 2;
            float top = stripH() + dp(4) + r * rowH();
            for (String k : row) {
                Key key = new Key(k);
                float kw = weight(k) * unit;
                key.rect.set(x + gapX / 2, top + gapY / 2, x + kw - gapX / 2, top + rowH() - gapY / 2);
                mKeys.add(key);
                x += kw;
            }
        }
    }

    private static float weight(String k) {
        switch (k) {
            case SPACE:
                return 5f;
            case SHIFT:
            case BACKSPACE:
            case TO_SYMBOLS:
            case TO_LETTERS:
            case ENTER:
                return 1.5f;
            default:
                return 1f;
        }
    }

    private @Nullable Key find(String label) {
        for (Key k : mKeys) {
            if (k.label.equals(label)) return k;
        }
        return null;
    }

    // ---------------------------------------------------------------- 桥接线程 API

    /** 一个字素对应的按键序列（UI 线程，按当前层/Shift 状态算）。 */
    private List<String> sequenceFor(String g) {
        List<String> seq = new ArrayList<>();
        if (g.equals("\n")) {
            seq.add(ENTER);
            return seq;
        }
        if (g.equals(" ")) {
            seq.add(SPACE);
            return seq;
        }
        if (g.equals(",") || g.equals(".")) {
            seq.add(g);
            return seq;
        }
        int cp = g.codePointAt(0);
        boolean asciiLetter = g.length() == 1 && ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z'));
        if (asciiLetter) {
            if (mSymbols) seq.add(TO_LETTERS);
            boolean upper = Character.isUpperCase(cp);
            if (upper && !mShift) seq.add(SHIFT);
            seq.add(g.toLowerCase());
            return seq;
        }
        if (onLayer(SYMBOLS, g)) {
            if (!mSymbols) seq.add(TO_SYMBOLS);
            seq.add(g);
            return seq;
        }
        // 键盘上没有的字（汉字、表情、其他符号）：当作候选词，手指点候选条。
        seq.add(CANDIDATE);
        return seq;
    }

    private static boolean onLayer(String[][] layer, String g) {
        for (String[] row : layer) {
            if (Arrays.asList(row).contains(g)) return true;
        }
        return false;
    }

    @Override
    public void press(String grapheme) {
        List<String> seq = ThreadUtils.runOnUiThreadBlocking(() -> {
            List<String> s = sequenceFor(grapheme);
            if (s.contains(CANDIDATE)) {
                mCandidate = grapheme;
                layoutChip();
                invalidate();
            }
            return s;
        });
        for (int i = 0; i < seq.size(); i++) {
            if (i > 0) sleep(70 + RNG.nextInt(80));
            tap(seq.get(i));
        }
    }

    @Override
    public void pressKey(String name) {
        String label;
        switch (name) {
            case "Enter":
                label = ENTER;
                break;
            case "Backspace":
                label = BACKSPACE;
                break;
            default:
                label = CANDIDATE;
                ThreadUtils.runOnUiThreadBlocking(() -> {
                    mCandidate = name;
                    layoutChip();
                    invalidate();
                });
                break;
        }
        tap(label);
    }

    @Override
    public void echo(String text) {
        ThreadUtils.postOnUiThread(() -> {
            mEcho = text;
            invalidate();
        });
    }

    /** 按一下某个键：手指落下、键高亮、按住 55–110 ms、抬起。桥接线程阻塞。 */
    private void tap(String label) {
        ThreadUtils.postOnUiThread(() -> down(label));
        sleep(55 + RNG.nextInt(55));
        ThreadUtils.postOnUiThread(() -> up(label));
    }

    private @Nullable LemurXTouchOverlay overlay() {
        return LemurXDeviceBridge.overlayIfAny();
    }

    private void down(String label) {
        RectF r = label.equals(CANDIDATE) ? mChipRect : rectOf(label);
        mPressed = label;
        mPressedAt = SystemClock.uptimeMillis();
        LemurXTouchOverlay o = overlay();
        if (o != null && r != null && !r.isEmpty()) {
            float x = r.centerX() + (float) (RNG.nextGaussian() * r.width() * 0.12);
            float y = r.centerY() + (float) (RNG.nextGaussian() * r.height() * 0.1);
            o.touch(this, x, y, /* compact= */ true);
        }
        invalidate();
    }

    private void up(String label) {
        LemurXTouchOverlay o = overlay();
        if (o != null) o.lift();
        mPressed = null;
        switch (label) {
            case SHIFT:
                mShift = !mShift;
                break;
            case TO_SYMBOLS:
                mSymbols = true;
                mShift = false;
                rebuild();
                break;
            case TO_LETTERS:
                mSymbols = false;
                rebuild();
                break;
            case CANDIDATE:
                mCandidate = null;
                mChipRect.setEmpty();
                break;
            default:
                if (mShift && label.length() == 1) mShift = false; // 单次 Shift
                break;
        }
        invalidate();
    }

    /** 候选词芯片的位置（候选条右侧）。 */
    private void layoutChip() {
        String cand = mCandidate;
        if (TextUtils.isEmpty(cand)) {
            mChipRect.setEmpty();
            return;
        }
        float tw = mLabelSmall.measureText(cand) + dp(28);
        float cx = getWidth() * 0.78f;
        mChipRect.set(cx - tw / 2, dp(6), cx + tw / 2, stripH() - dp(6));
    }

    private @Nullable RectF rectOf(String label) {
        Key k = find(label);
        return k == null ? null : k.rect;
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), mBg);

        // 候选 / 回显条
        float stripMid = stripH() / 2;
        float baseStrip = stripMid - (mStrip.descent() + mStrip.ascent()) / 2;
        String echo = mEcho;
        if (!TextUtils.isEmpty(echo)) {
            float maxW = getWidth() * 0.55f;
            String shown = TextUtils.ellipsize(echo, new android.text.TextPaint(mStrip), maxW,
                    TextUtils.TruncateAt.START).toString();
            canvas.drawText(shown, dp(16), baseStrip, mStrip);
        }
        String cand = mCandidate;
        if (!TextUtils.isEmpty(cand)) {
            if (mChipRect.isEmpty()) layoutChip();
            boolean pressed = CANDIDATE.equals(mPressed);
            if (pressed) {
                canvas.drawRoundRect(mChipRect, dp(16), dp(16), mCapPressed);
            }
            canvas.drawRoundRect(mChipRect, dp(16), dp(16), mChip);
            mLabelSmall.setColor(pressed ? 0xFFFFFFFF : mInk);
            float base = mChipRect.centerY() - (mLabelSmall.descent() + mLabelSmall.ascent()) / 2;
            canvas.drawText(cand, mChipRect.centerX(), base, mLabelSmall);
        }

        // 键帽
        Key pressedKey = null;
        for (Key k : mKeys) {
            boolean pressed = k.label.equals(mPressed);
            boolean lit = pressed || (k.label.equals(SHIFT) && mShift);
            Paint cap = lit ? mCapPressed : (k.special() ? mCapSpecial : mCap);
            canvas.drawRoundRect(k.rect, dp(7), dp(7), cap);
            String text = k.label.equals(SPACE) ? "" : k.label;
            if (!k.special() && mShift && !mSymbols) text = text.toUpperCase();
            Paint lp = k.special() ? mLabelSmall : mLabel;
            lp.setColor(lit ? 0xFFFFFFFF : mInk);
            float base = k.rect.centerY() - (lp.descent() + lp.ascent()) / 2;
            canvas.drawText(text, k.rect.centerX(), base, lp);
            if (pressed) pressedKey = k;
        }

        // 预览气泡：和真机一样，按住的字母在键帽上方放大显示。
        if (pressedKey != null && !pressedKey.special()) {
            String text = mShift && !mSymbols ? pressedKey.label.toUpperCase() : pressedKey.label;
            float w = pressedKey.rect.width() * 1.4f;
            float h = pressedKey.rect.height() * 1.15f;
            float cx = pressedKey.rect.centerX();
            float bottom = pressedKey.rect.top - dp(6);
            RectF bubble = new RectF(cx - w / 2, bottom - h, cx + w / 2, bottom);
            canvas.drawRoundRect(bubble, dp(8), dp(8), mCap);
            float base = bubble.centerY() - (mPreview.descent() + mPreview.ascent()) / 2;
            canvas.drawText(text, cx, base, mPreview);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
