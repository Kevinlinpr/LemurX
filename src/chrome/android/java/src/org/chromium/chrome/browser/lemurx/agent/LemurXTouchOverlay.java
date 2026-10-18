// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.agent;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import org.chromium.build.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Agent 手指的可视化。
 *
 * <ul>
 *   <li>瞄准：落指前 {@code aim()} 在目标处画一个收缩的靶环——用户先看到"手指要去哪"，再看到它落下。
 *   <li>按住：{@code touch()} 之后是一个实心圆点 + 外环，一直停到抬手；滑动时拖出轨迹。
 *   <li>抬手：{@code lift()} 之后圆点炸开成一圈涟漪，1 s 内淡出；轨迹随后淡出。
 *   <li>字幕：{@code caption()} 在底部（键盘上方）显示一枚药丸，回显 agent 正在打的字 / 按的键。
 * </ul>
 *
 * <p>加在 android.R.id.content 最上层，完全不接收触摸（{@link #onTouchEvent} 返回 false 且不可
 * 点击），只负责让用户看见"是谁在动我的屏幕、动了哪里、打了什么"。agent 的截图拷的是合成器表面
 * （SurfaceView），不含这个视图，所以 agent 自己看到的截图不会被污染。
 */
final class LemurXTouchOverlay extends View {
    /** 品牌色：Logo 渐变 #14D5E6 → #018FE3 的中间调。 */
    static final int ACCENT = 0xFF0FA9E4;
    private static final long RIPPLE_MS = 700;
    private static final long TRAIL_MS = 1_200;
    private static final long CAPTION_HOLD_MS = 2_000;

    private static final class Ripple {
        float x;
        float y;
        long at;
        float scale; // 键盘上的连续敲击用小一号的涟漪，免得盖住键帽
    }

    private static final class Trail {
        final List<float[]> points = new ArrayList<>();
        long end;
    }

    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTrail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPillBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPillText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Ripple> mRipples = new ArrayList<>();
    private final List<Trail> mTrails = new ArrayList<>();
    private final float mDensity;

    // 瞄准中的目标（落指前）。
    private boolean mAiming;
    private float mAimX;
    private float mAimY;
    private long mAimStart;
    private long mAimMs;
    // 按住中的手指。
    private boolean mPressed;
    private boolean mCompact;
    private float mPressX;
    private float mPressY;
    private @Nullable Trail mActive;
    // 字幕。
    private @Nullable String mCaption;
    private long mCaptionAt;

    LemurXTouchOverlay(Context context) {
        super(context);
        mDensity = context.getResources().getDisplayMetrics().density;
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(3 * mDensity);
        mStroke.setColor(ACCENT);
        mFill.setStyle(Paint.Style.FILL);
        mFill.setColor(ACCENT);
        mTrail.setStyle(Paint.Style.STROKE);
        mTrail.setStrokeWidth(5 * mDensity);
        mTrail.setStrokeCap(Paint.Cap.ROUND);
        mTrail.setStrokeJoin(Paint.Join.ROUND);
        mTrail.setColor(ACCENT);
        mPillBg.setStyle(Paint.Style.FILL);
        mPillBg.setColor(0xF2018FE3);
        mPillText.setColor(Color.WHITE);
        mPillText.setTextSize(15 * mDensity);
        mPillText.setTypeface(Typeface.DEFAULT_BOLD);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    /** 坐标是 {@code source} 视图内的像素，这里换算成覆盖层自己的坐标。 */
    private float[] map(View source, float x, float y) {
        int[] a = new int[2];
        int[] b = new int[2];
        source.getLocationInWindow(a);
        getLocationInWindow(b);
        return new float[] {x + a[0] - b[0], y + a[1] - b[1]};
    }

    /** 落指前 {@code leadMs} 调用：在目标处画收缩的靶环。 */
    void aim(View source, float x, float y, long leadMs) {
        float[] p = map(source, x, y);
        mAiming = true;
        mAimX = p[0];
        mAimY = p[1];
        mAimStart = SystemClock.uptimeMillis();
        mAimMs = Math.max(1, leadMs);
        postInvalidateOnAnimation();
    }

    void touch(View source, float x, float y) {
        touch(source, x, y, false);
    }

    /** {@code compact}：小号指示（键盘敲击）。 */
    void touch(View source, float x, float y, boolean compact) {
        float[] p = map(source, x, y);
        mAiming = false;
        mPressed = true;
        mCompact = compact;
        mPressX = p[0];
        mPressY = p[1];
        Trail t = new Trail();
        t.points.add(p);
        mActive = t;
        mTrails.add(t);
        postInvalidateOnAnimation();
    }

    void move(View source, float x, float y) {
        float[] p = map(source, x, y);
        mPressX = p[0];
        mPressY = p[1];
        if (mActive != null) mActive.points.add(p);
        postInvalidateOnAnimation();
    }

    void lift() {
        mAiming = false;
        if (mPressed) {
            Ripple r = new Ripple();
            r.x = mPressX;
            r.y = mPressY;
            r.at = SystemClock.uptimeMillis();
            r.scale = mCompact ? 0.55f : 1f;
            mRipples.add(r);
        }
        mPressed = false;
        if (mActive != null) mActive.end = SystemClock.uptimeMillis();
        mActive = null;
        postInvalidateOnAnimation();
    }

    /** 底部字幕：正在打的字 / 按的键；2 s 没更新自动消失。 */
    void caption(@Nullable String text) {
        mCaption = TextUtils.isEmpty(text) ? null : text;
        mCaptionAt = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        boolean more = false;

        if (mAiming) {
            float u = Math.min(1, (now - mAimStart) / (float) mAimMs);
            float radius = mDensity * (34 - 16 * u);
            mStroke.setAlpha(255);
            canvas.drawCircle(mAimX, mAimY, radius, mStroke);
            // 十字准星
            float arm = mDensity * 8;
            canvas.drawLine(mAimX - radius - arm, mAimY, mAimX - radius + arm, mAimY, mStroke);
            canvas.drawLine(mAimX + radius - arm, mAimY, mAimX + radius + arm, mAimY, mStroke);
            canvas.drawLine(mAimX, mAimY - radius - arm, mAimX, mAimY - radius + arm, mStroke);
            canvas.drawLine(mAimX, mAimY + radius - arm, mAimX, mAimY + radius + arm, mStroke);
            more = true;
        }

        for (Iterator<Trail> it = mTrails.iterator(); it.hasNext();) {
            Trail t = it.next();
            float u = t.end == 0 ? 0 : (now - t.end) / (float) TRAIL_MS;
            if (u >= 1) {
                it.remove();
                continue;
            }
            if (t.points.size() < 2) continue;
            more = true;
            mTrail.setAlpha((int) (220 * (1 - u)));
            for (int i = 1; i < t.points.size(); i++) {
                float[] a = t.points.get(i - 1);
                float[] b = t.points.get(i);
                canvas.drawLine(a[0], a[1], b[0], b[1], mTrail);
            }
        }

        if (mPressed) {
            float k = mCompact ? 0.7f : 1f;
            mFill.setAlpha(200);
            canvas.drawCircle(mPressX, mPressY, mDensity * 14 * k, mFill);
            mStroke.setAlpha(255);
            canvas.drawCircle(mPressX, mPressY, mDensity * 21 * k, mStroke);
            more = true;
        }

        for (Iterator<Ripple> it = mRipples.iterator(); it.hasNext();) {
            Ripple r = it.next();
            float u = (now - r.at) / (RIPPLE_MS * r.scale);
            if (u >= 1) {
                it.remove();
                continue;
            }
            more = true;
            float radius = mDensity * (14 + 20 * u) * r.scale; // 只比指尖大一圈，不要满屏扩散
            mFill.setAlpha((int) (140 * (1 - u)));
            mStroke.setAlpha((int) (255 * (1 - u)));
            canvas.drawCircle(r.x, r.y, radius, mFill);
            canvas.drawCircle(r.x, r.y, radius, mStroke);
            // 中心留一个点，涟漪散开后还能看清按的是哪里。
            mFill.setAlpha((int) (230 * (1 - u)));
            canvas.drawCircle(r.x, r.y, mDensity * 6, mFill);
        }

        if (mCaption != null) {
            long age = now - mCaptionAt;
            if (age > CAPTION_HOLD_MS + 400) {
                mCaption = null;
            } else {
                float alpha = age <= CAPTION_HOLD_MS ? 1 : 1 - (age - CAPTION_HOLD_MS) / 400f;
                String text = mCaption.length() > 36 ? "…" + mCaption.substring(mCaption.length() - 36) : mCaption;
                float tw = mPillText.measureText(text);
                float padX = 16 * mDensity;
                float h = 40 * mDensity;
                float w = tw + padX * 2;
                float cx = getWidth() / 2f;
                // Chromium 自己处理键盘 inset，content 视图不会随键盘缩小；这里按窗口可见区
                // 把药丸放到键盘上沿，没键盘时则抬到 LemurX 底栏之上。
                Rect vis = new Rect();
                getWindowVisibleDisplayFrame(vis);
                int[] loc = new int[2];
                getLocationOnScreen(loc);
                float visBottom = Math.min(getHeight(), vis.bottom - loc[1]);
                boolean keyboard = visBottom < getHeight() - 120 * mDensity;
                float bottom = visBottom - (keyboard ? 16 : 112) * mDensity;
                RectF pill = new RectF(cx - w / 2, bottom - h, cx + w / 2, bottom);
                mPillBg.setAlpha((int) (0xE6 * alpha));
                mPillText.setAlpha((int) (255 * alpha));
                canvas.drawRoundRect(pill, h / 2, h / 2, mPillBg);
                float baseline = pill.centerY() - (mPillText.descent() + mPillText.ascent()) / 2;
                canvas.drawText(text, pill.left + padX, baseline, mPillText);
                more = true;
            }
        }

        if (more || mActive != null) postInvalidateOnAnimation();
    }
}
