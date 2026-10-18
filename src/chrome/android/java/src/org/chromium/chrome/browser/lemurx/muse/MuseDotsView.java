// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.LinearInterpolator;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;

/**
 * Agent 正在想 / 正在干活时的"三个点跳跃"指示器。
 *
 * <p>三个圆点依次向上弹起再落下（正弦的正半周，错开相位），像聊天软件里的"对方正在输入"。
 * 自己画而不是三个 View 各挂一个动画：只有一个 Animator、一次 invalidate，列表里重排也更稳。
 * 挂到窗口上就开始动，离开窗口或不可见时停下，不白耗电。
 */
@NullMarked
class MuseDotsView extends View {
    private static final int DOT_COUNT = 3;
    private static final long PERIOD_MS = 1100;
    /** 相邻两个点之间的相位差（占整个周期的比例）。 */
    private static final float PHASE_STEP = 0.15f;
    /** 每个点在周期里"在空中"的时长比例，其余时间停在底线上。 */
    private static final float AIR_RATIO = 0.55f;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDot;
    private final float mGap;
    private final float mJump;
    private @Nullable ValueAnimator mAnimator;
    private float mPhase;

    MuseDotsView(Context context, int color) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        mDot = 7f * density;
        mGap = 5f * density;
        mJump = 5f * density;
        mPaint.setColor(color);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = Math.round(DOT_COUNT * mDot + (DOT_COUNT - 1) * mGap) + getPaddingLeft() + getPaddingRight();
        int h = Math.round(mDot + mJump) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(
                resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float r = mDot / 2f;
        float baseline = getHeight() - getPaddingBottom() - r;
        float x = getPaddingLeft() + r;
        for (int i = 0; i < DOT_COUNT; i++) {
            // 这个点在周期里的位置，错开相位；只在前 AIR_RATIO 段里起跳。
            float t = (mPhase - i * PHASE_STEP + 1f) % 1f;
            float lift = 0f;
            float alpha = 0.45f;
            if (t < AIR_RATIO) {
                float s = (float) Math.sin(Math.PI * t / AIR_RATIO);
                lift = s * mJump;
                alpha = 0.45f + 0.55f * s;
            }
            mPaint.setAlpha(Math.round(alpha * 255));
            canvas.drawCircle(x, baseline - lift, r, mPaint);
            x += mDot + mGap;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        syncAnimator();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        syncAnimator();
    }

    private void syncAnimator() {
        if (isAttachedToWindow() && isShown()) {
            start();
        } else {
            stop();
        }
    }

    private void start() {
        if (mAnimator != null) return;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(PERIOD_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(
                a -> {
                    mPhase = (float) a.getAnimatedValue();
                    invalidate();
                });
        animator.start();
        mAnimator = animator;
    }

    private void stop() {
        ValueAnimator animator = mAnimator;
        if (animator == null) return;
        animator.cancel();
        mAnimator = null;
    }
}
