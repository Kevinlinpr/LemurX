// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.LinearInterpolator;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.lemurx.LemurXHaptics;

/**
 * Agent 表情球：圆身、跟随注视、32 种情绪、3D 彩带。
 *
 * <p>造型与 emotionId 契约来自 grok-ball（MIT，https://github.com/tycoding/grok-ball）。
 * 原生 Canvas 画，不挂 WebView。点一下自旋并甩彩带；长按打开 32 种表情。
 */
@NullMarked
class MuseBallView extends View {
    private static final int SPARK_COUNT = 14;
    private static final int RIBBON_COUNT = 3;
    private static final int RIBBON_LEN = 22;
    private static final int ZZZ_COUNT = 3;
    private static final int BODY = 0xFF1A1A1A;
    private static final int EYE = 0xFFF5F2EA;
    private static final int PUPIL = 0xFF121212;
    private static final int SHINE = 0x66FFFFFF;
    private static final int[] RIBBON_COLORS = {
            0xFF7EEAE0, 0xFFFFC56A, 0xFFFF8A80, 0xFFB39DDB, 0xFF80CBC4
    };

    private final Paint mBodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEyePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPupilPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mOrbitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mZzzPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mEyePath = new Path();
    private final RectF mEyeOval = new RectF();
    private final Spark[] mSparks = new Spark[SPARK_COUNT];
    private final Ribbon[] mRibbons = new Ribbon[RIBBON_COUNT];
    private final Zzz[] mZzz = new Zzz[ZZZ_COUNT];
    private final int[] mLoc = new int[2];
    private final int mOutlineColor;
    private final int[] mSparkColors;
    private final float mSize;
    private final float mDensity;
    private final boolean mEyeBoost;

    private @Nullable ValueAnimator mAnimator;
    private @Nullable RadialGradient mBodyShader;
    private MuseBallEmotion mSpec = MuseBallEmotion.of(MuseBallEmotion.IDLE);
    private String mEmotionId = MuseBallEmotion.IDLE;
    private int mDrawnBody;
    private float mShaderR;
    private float mShaderCx;
    private float mShaderCy;
    private long mLastMs;
    private long mBlinkAt;
    private float mBlink;
    private float mLeftBlink;
    private float mLookX;
    private float mLookY;
    private float mGazeX;
    private float mGazeY;
    private boolean mHasGaze;
    private float mSpin;
    private float mSpinTo;
    private float mBreath;
    private float mTime;
    private float mShake;
    private float mFlash;
    private long mAnticAt;

    MuseBallView(Context context) {
        this(context, 36);
    }

    MuseBallView(Context context, int sizeDp) {
        super(context);
        mDensity = context.getResources().getDisplayMetrics().density;
        mSize = sizeDp * mDensity;
        mEyeBoost = sizeDp <= 40;
        mOutlineColor = MusePage.outline(context);
        mSparkColors = new int[] {
                MusePage.chipText(context),
                MusePage.warn(context),
                MusePage.error(context),
                MusePage.ok(context),
                0xFFFF8A80,
        };
        mEyePaint.setColor(EYE);
        mPupilPaint.setColor(PUPIL);
        mShinePaint.setColor(SHINE);
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeWidth(1.15f * mDensity);
        mStrokePaint.setColor(mOutlineColor);
        mOrbitPaint.setStyle(Paint.Style.STROKE);
        mOrbitPaint.setStrokeCap(Paint.Cap.ROUND);
        mOrbitPaint.setStrokeJoin(Paint.Join.ROUND);
        mShadowPaint.setColor(0x28000000);
        mZzzPaint.setColor(MusePage.muted(context));
        mZzzPaint.setTextAlign(Paint.Align.CENTER);
        mZzzPaint.setFakeBoldText(true);
        for (int i = 0; i < SPARK_COUNT; i++) mSparks[i] = new Spark();
        for (int i = 0; i < RIBBON_COUNT; i++) mRibbons[i] = new Ribbon();
        for (int i = 0; i < ZZZ_COUNT; i++) mZzz[i] = new Zzz();
        setClickable(true);
        setOnClickListener(v -> poke());
        mBlinkAt = SystemClock.uptimeMillis() + 1800;
        mAnticAt = SystemClock.uptimeMillis() + 9000;
        setContentDescription(context.getString(R.string.lemurx_agent_ball_hint));
    }

    String emotionId() {
        return mEmotionId;
    }

    void setEmotion(String id) {
        MuseBallEmotion next = MuseBallEmotion.of(id);
        boolean same = next.id.equals(mEmotionId);
        mSpec = next;
        mEmotionId = next.id;
        if (same) return;
        mFlash = next.id.equals(MuseBallEmotion.ERROR) ? 1f : 0f;
        if (next.shake) mShake = 1f;
        if (next.ribbons) {
            mSpinTo = mSpin + (float) (Math.PI * 2.0);
            spawnRibbons(false);
        }
        if (next.confetti) burst(false);
        if (next.orbit) ensureOrbitRibbon();
        if (next.zzz) resetZzz();
        invalidate();
    }

    /** 屏幕坐标，眼睛朝手指那边看。睡眠 / 关机会关掉跟随。 */
    void followScreen(float rawX, float rawY) {
        if (!mSpec.gaze) return;
        mHasGaze = true;
        mGazeX = rawX;
        mGazeY = rawY;
    }

    void poke() {
        poke(/*haptic=*/ true);
    }

    private void poke(boolean haptic) {
        if (haptic) LemurXHaptics.tick(this);
        mSpinTo = mSpin + (float) (Math.PI * 2.0);
        spawnRibbons(true);
        burst(true);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = Math.round(mSize);
        setMeasuredDimension(
                resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w * 0.5f;
        float cy = h * 0.52f;
        float r = Math.min(w, h) * (mEyeBoost ? 0.36f : 0.32f);
        float breathe = 1f + 0.04f * (float) Math.sin(mBreath);
        if (mSpec.pulse) breathe += 0.03f * (float) Math.sin(mTime * (Math.PI * 2.0) / 0.68);
        float jx = 0f;
        float jy = 0f;
        if (mSpec.jitter || mShake > 0f) {
            float j = (mSpec.jitter ? 0.55f : 0f) + mShake * 2.2f;
            jx = j * (float) Math.sin(mTime * 31f) * mDensity;
            jy = j * (float) Math.cos(mTime * 27f) * mDensity * 0.4f;
        }
        float bodyR = r * breathe;
        int bodyColor = bodyColor();
        canvas.save();
        canvas.translate(cx + jx, cy + jy);
        canvas.rotate(mSpec.tilt + mShake * 6f * (float) Math.sin(mTime * 18f));
        drawRibbons(canvas, 0f, 0f, bodyR, /*back=*/ true);
        mShadowPaint.setAlpha(40);
        canvas.drawOval(-bodyR * 0.72f, h * 0.42f - cy, bodyR * 0.72f, h * 0.5f - cy, mShadowPaint);
        ensureBodyShader(0f, -bodyR * 0.08f, bodyR, bodyColor);
        canvas.drawCircle(0f, 0f, bodyR, mBodyPaint);
        canvas.drawCircle(0f, 0f, bodyR, mStrokePaint);

        float facing = (float) Math.cos(mSpin);
        if (facing > 0.12f) {
            drawEye(canvas, 0f, 0f, bodyR, -1f, facing);
            drawEye(canvas, 0f, 0f, bodyR, 1f, facing);
            drawBrows(canvas, 0f, 0f, bodyR, facing);
        }
        drawRibbons(canvas, 0f, 0f, bodyR, /*back=*/ false);
        drawSparks(canvas, 0f, 0f);
        if (mSpec.zzz) drawZzz(canvas, 0f, 0f, bodyR);
        canvas.restore();
    }

    private int bodyColor() {
        int color = mSpec.color != 0 ? mSpec.color : BODY;
        if (mFlash > 0f) {
            float wave = 0.5f + 0.5f * (float) Math.sin(mTime * 22f);
            color = mix(color, 0xFFF6F3EC, wave * mFlash);
        }
        return color;
    }

    private void drawEye(Canvas canvas, float cx, float cy, float r, float side, float facing) {
        float open = eyeOpen(side);
        float scale = side < 0 ? mSpec.leftScale : mSpec.rightScale;
        float yOff = side < 0 ? mSpec.leftY : mSpec.rightY;
        float ex = cx + side * r * (mSpec.converge ? 0.22f : 0.28f) * facing;
        float ey = cy - r * 0.10f + yOff * r * 0.22f;
        float ew = r * (mEyeBoost ? 0.36f : 0.30f) * (0.55f + 0.45f * facing) * scale;
        float eh = r * (mEyeBoost ? 0.40f : 0.34f) * open * scale;
        if (ew < 0.5f || eh < 0.5f) return;

        mEyeOval.set(ex - ew, ey - eh, ex + ew, ey + eh);
        mEyePath.reset();
        mEyePath.addOval(mEyeOval, Path.Direction.CW);
        canvas.save();
        canvas.rotate(side * -7f, ex, ey);
        canvas.drawPath(mEyePath, mEyePaint);
        canvas.clipPath(mEyePath);
        float px = ex + mLookX * ew * 0.42f + side * ew * 0.06f;
        float py = ey + mLookY * eh * 0.38f;
        float pr = Math.min(ew, eh) * 0.46f;
        canvas.drawCircle(px, py, pr, mPupilPaint);
        mShinePaint.setColor(SHINE);
        canvas.drawCircle(px - pr * 0.32f, py - pr * 0.38f, pr * 0.28f, mShinePaint);
        canvas.restore();
    }

    private void drawBrows(Canvas canvas, float cx, float cy, float r, float facing) {
        if (mSpec.brow <= 0f && !mSpec.id.equals(MuseBallEmotion.THINKING)
                && !mSpec.id.equals(MuseBallEmotion.DONE)) {
            return;
        }
        mOrbitPaint.setStyle(Paint.Style.STROKE);
        mOrbitPaint.setStrokeWidth(1.8f * mDensity);
        mOrbitPaint.setAlpha(220);
        mOrbitPaint.setColor(mSpec.brow > 0f ? 0xFFE8C4C0 : EYE);
        float y = cy - r * 0.42f;
        float w = r * 0.22f * facing;
        float lift = mSpec.brow > 0f ? r * 0.06f : (mSpec.id.equals(MuseBallEmotion.THINKING) ? -r * 0.04f : r * 0.02f);
        float tilt = mSpec.brow > 0f ? 18f : (mSpec.id.equals(MuseBallEmotion.THINKING) ? -12f : -6f);
        canvas.save();
        canvas.rotate(-tilt, cx - r * 0.28f, y);
        canvas.drawLine(cx - r * 0.28f - w, y + lift, cx - r * 0.28f + w, y, mOrbitPaint);
        canvas.restore();
        canvas.save();
        canvas.rotate(tilt, cx + r * 0.28f, y);
        canvas.drawLine(cx + r * 0.28f - w, y, cx + r * 0.28f + w, y + lift, mOrbitPaint);
        canvas.restore();
    }

    private void drawRibbons(Canvas canvas, float cx, float cy, float r, boolean back) {
        mOrbitPaint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < RIBBON_COUNT; i++) {
            Ribbon ribbon = mRibbons[i];
            if (ribbon.life <= 0f && !ribbon.orbit) continue;
            for (int p = 1; p < ribbon.n; p++) {
                float z0 = ribbon.z[p - 1];
                float z1 = ribbon.z[p];
                float z = (z0 + z1) * 0.5f;
                boolean behind = z < 0f;
                if (behind != back) continue;
                float depth = 1f / (1f + Math.max(-0.6f, z) * 0.55f);
                float t = p / (float) RIBBON_LEN;
                mOrbitPaint.setColor(RIBBON_COLORS[(i + p / 4) % RIBBON_COLORS.length]);
                int alpha = Math.round(255f * ribbon.life * t * (behind ? 0.45f : 0.95f));
                mOrbitPaint.setAlpha(Math.max(0, Math.min(255, alpha)));
                mOrbitPaint.setStrokeWidth((2.8f + 4.2f * t) * mDensity * depth * (r / (36f * mDensity)));
                canvas.drawLine(cx + ribbon.x[p - 1] * r * depth, cy + ribbon.y[p - 1] * r * depth,
                        cx + ribbon.x[p] * r * depth, cy + ribbon.y[p] * r * depth, mOrbitPaint);
            }
        }
    }

    private void drawSparks(Canvas canvas, float cx, float cy) {
        for (int i = 0; i < SPARK_COUNT; i++) {
            Spark s = mSparks[i];
            if (s.life <= 0f) continue;
            mSparkPaint.setColor(s.color);
            mSparkPaint.setAlpha(Math.round(255f * Math.min(1f, s.life)));
            canvas.drawCircle(cx + s.x, cy + s.y, s.size * Math.max(0.2f, s.life), mSparkPaint);
        }
    }

    private void drawZzz(Canvas canvas, float cx, float cy, float r) {
        mZzzPaint.setTextSize(r * 0.28f);
        for (int i = 0; i < ZZZ_COUNT; i++) {
            Zzz z = mZzz[i];
            mZzzPaint.setAlpha(Math.round(180f * z.life));
            canvas.drawText("z", cx + r * 0.7f + z.x, cy - r * 0.55f + z.y, mZzzPaint);
        }
    }

    private float eyeOpen(float side) {
        float base = mSpec.openness;
        float blink = mBlink;
        if (mSpec.altBlink && side > 0f) blink = mLeftBlink;
        float lid = 0f;
        if (blink > 0f) {
            lid = blink < 0.5f ? blink * 2f : (1f - blink) * 2f;
        }
        return Math.max(0.06f, base * (1f - lid));
    }

    private void ensureBodyShader(float cx, float cy, float r, int color) {
        if (mBodyShader != null && mShaderR == r && mDrawnBody == color && mShaderCx == cx
                && mShaderCy == cy) {
            return;
        }
        int hi = mix(color, Color.WHITE, 0.22f);
        mBodyShader = new RadialGradient(
                cx - r * 0.28f, cy - r * 0.34f, r * 1.35f, hi, color, Shader.TileMode.CLAMP);
        mBodyPaint.setShader(mBodyShader);
        mShaderR = r;
        mDrawnBody = color;
        mShaderCx = cx;
        mShaderCy = cy;
    }

    private void burst(boolean fromTap) {
        float speed = fromTap ? 1f : 0.75f;
        for (int i = 0; i < SPARK_COUNT; i++) {
            Spark s = mSparks[i];
            double a = (Math.PI * 2.0 * i) / SPARK_COUNT + (fromTap ? 0.4 : 0.0);
            float v = (38f + (i % 4) * 16f) * speed * mDensity;
            s.vx = (float) Math.cos(a) * v;
            s.vy = (float) Math.sin(a) * v - 22f * mDensity;
            s.x = 0f;
            s.y = 0f;
            s.life = 1f;
            s.size = (2.1f + (i % 3)) * mDensity;
            s.color = mSparkColors[i % mSparkColors.length];
        }
    }

    private void spawnRibbons(boolean fromTap) {
        for (int i = 0; i < RIBBON_COUNT; i++) {
            Ribbon ribbon = mRibbons[i];
            ribbon.orbit = false;
            ribbon.life = 1f;
            ribbon.n = 1;
            ribbon.theta = (float) (i * 2.1 + mTime);
            ribbon.speed = (fromTap ? 5.4f : 4.2f) + i * 0.7f;
            ribbon.radius = 1.15f + i * 0.08f;
            ribbon.tilt = 0.6f + i * 0.5f;
            ribbon.x[0] = 0f;
            ribbon.y[0] = 0f;
            ribbon.z[0] = 1f;
        }
    }

    private void ensureOrbitRibbon() {
        Ribbon ribbon = mRibbons[0];
        ribbon.orbit = true;
        ribbon.life = 1f;
        ribbon.speed = 1.6f;
        ribbon.radius = 1.22f;
        ribbon.tilt = 0.35f;
        if (ribbon.n == 0) ribbon.n = 1;
    }

    private void resetZzz() {
        for (int i = 0; i < ZZZ_COUNT; i++) {
            mZzz[i].life = 1f - i * 0.28f;
            mZzz[i].x = i * 6f * mDensity;
            mZzz[i].y = i * 10f * mDensity;
        }
    }

    private void tick(float dt) {
        mTime += dt;
        mBreath += dt * ((float) (Math.PI * 2.0) / Math.max(0.4f, mSpec.breathe));
        if (mShake > 0f) mShake = Math.max(0f, mShake - dt * 1.4f);
        if (mFlash > 0f && !mSpec.id.equals(MuseBallEmotion.ERROR)) {
            mFlash = Math.max(0f, mFlash - dt * 1.6f);
        }

        long now = SystemClock.uptimeMillis();
        tickBlink(dt, now);
        tickLook(dt);
        tickSpin(dt);
        tickRibbons(dt);
        tickSparks(dt);
        if (mSpec.zzz) tickZzz(dt);
        if (mSpec.antics && !mEyeBoost && now >= mAnticAt) {
            mAnticAt = now + 9000 + (long) (Math.random() * 8000);
            poke(/*haptic=*/ false);
        }
    }

    private void tickBlink(float dt, long now) {
        if (mSpec.openness <= 0.12f) {
            mBlink = 0f;
            mLeftBlink = 0f;
            return;
        }
        mBlink = stepBlink(mBlink, dt);
        if (mSpec.altBlink) {
            mLeftBlink = stepBlink(mLeftBlink, dt);
            if (mBlink <= 0f && now >= mBlinkAt) {
                mBlink = 0.01f;
                mBlinkAt = now + 700;
            }
            if (mLeftBlink <= 0f && now >= mBlinkAt + 380) {
                mLeftBlink = 0.01f;
            }
            return;
        }
        if (mBlink > 0f) return;
        if (now >= mBlinkAt) {
            mBlink = 0.01f;
            mBlinkAt = now + 1600 + (long) (Math.random() * 2400);
        }
    }

    private static float stepBlink(float blink, float dt) {
        if (blink <= 0f) return 0f;
        blink += dt / 0.16f;
        return blink >= 1f ? 0f : blink;
    }

    private void tickLook(float dt) {
        float restX = mSpec.restX;
        float restY = mSpec.restY;
        if (mSpec.ampX != 0f) {
            restX += mSpec.ampX * (float) Math.sin(mTime * (Math.PI * 2.0) / mSpec.periodX);
        }
        if (mSpec.ampY != 0f) {
            restY += mSpec.ampY * (float) Math.sin(mTime * (Math.PI * 2.0) / mSpec.periodY);
        }
        if (mSpec.scan) {
            float t = (mTime % mSpec.periodX) / mSpec.periodX;
            restX = mSpec.ampX * (t < 0.5f ? (t * 4f - 1f) : (3f - t * 4f));
        }
        float wantX = restX;
        float wantY = restY;
        if (mHasGaze && mSpec.gaze && getWidth() > 0) {
            getLocationOnScreen(mLoc);
            float gx = (mGazeX - (mLoc[0] + getWidth() * 0.5f)) / (getWidth() * 1.8f);
            float gy = (mGazeY - (mLoc[1] + getHeight() * 0.5f)) / (getHeight() * 1.8f);
            gx = clamp(gx, -1f, 1f);
            gy = clamp(gy, -1f, 1f);
            float mix = Math.abs(mSpec.restY) > 0.5f ? 0.32f : 0.85f;
            wantX = restX * (1f - mix) + gx * mix;
            wantY = restY * (1f - mix) + gy * mix;
        }
        float follow = 1f - (float) Math.exp(-dt * 10f);
        mLookX += (wantX - mLookX) * follow;
        mLookY += (wantY - mLookY) * follow;
    }

    private void tickSpin(float dt) {
        if (mSpin < mSpinTo) {
            mSpin += dt * 7.2f;
            if (mSpin > mSpinTo) mSpin = mSpinTo;
        } else if (mSpin > (float) (Math.PI * 2.0)) {
            mSpin %= (float) (Math.PI * 2.0);
            mSpinTo = mSpin;
        }
    }

    private void tickRibbons(float dt) {
        if (mSpec.orbit) ensureOrbitRibbon();
        for (int i = 0; i < RIBBON_COUNT; i++) {
            Ribbon ribbon = mRibbons[i];
            if (ribbon.life <= 0f && !ribbon.orbit) continue;
            if (ribbon.orbit && !mSpec.orbit && ribbon.life > 0f) {
                ribbon.orbit = false;
            }
            ribbon.theta += ribbon.speed * dt;
            if (!ribbon.orbit) {
                ribbon.speed *= (1f - dt * 0.55f);
                ribbon.life -= dt * 0.28f;
            }
            float x = (float) Math.sin(ribbon.theta) * ribbon.radius;
            float z = (float) Math.cos(ribbon.theta) * ribbon.radius * 0.62f;
            float y = (float) Math.sin(ribbon.theta * 1.35 + ribbon.tilt) * ribbon.radius * 0.28f
                    - 0.08f;
            if (ribbon.n < RIBBON_LEN) {
                ribbon.x[ribbon.n] = x;
                ribbon.y[ribbon.n] = y;
                ribbon.z[ribbon.n] = z;
                ribbon.n++;
            } else {
                for (int p = 1; p < RIBBON_LEN; p++) {
                    ribbon.x[p - 1] = ribbon.x[p];
                    ribbon.y[p - 1] = ribbon.y[p];
                    ribbon.z[p - 1] = ribbon.z[p];
                }
                ribbon.x[RIBBON_LEN - 1] = x;
                ribbon.y[RIBBON_LEN - 1] = y;
                ribbon.z[RIBBON_LEN - 1] = z;
            }
        }
    }

    private void tickSparks(float dt) {
        for (int i = 0; i < SPARK_COUNT; i++) {
            Spark s = mSparks[i];
            if (s.life <= 0f) continue;
            s.vy += 110f * dt * mDensity;
            s.x += s.vx * dt;
            s.y += s.vy * dt;
            s.life -= dt * 1.2f;
        }
    }

    private void tickZzz(float dt) {
        for (int i = 0; i < ZZZ_COUNT; i++) {
            Zzz z = mZzz[i];
            z.y -= 18f * dt * mDensity;
            z.x += 6f * dt * mDensity;
            z.life -= dt * 0.45f;
            if (z.life <= 0f) {
                z.life = 1f;
                z.x = i * 4f * mDensity;
                z.y = 8f * mDensity;
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mLastMs = SystemClock.uptimeMillis();
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
        mLastMs = SystemClock.uptimeMillis();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(
                a -> {
                    long now = SystemClock.uptimeMillis();
                    float dt = Math.min(0.05f, (now - mLastMs) / 1000f);
                    mLastMs = now;
                    tick(dt);
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

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int mix(int a, int b, float t) {
        t = clamp(t, 0f, 1f);
        int ra = Color.red(a);
        int ga = Color.green(a);
        int ba = Color.blue(a);
        return Color.argb(255, ra + Math.round((Color.red(b) - ra) * t),
                ga + Math.round((Color.green(b) - ga) * t),
                ba + Math.round((Color.blue(b) - ba) * t));
    }

    private static final class Spark {
        float x;
        float y;
        float vx;
        float vy;
        float life;
        float size;
        int color;
    }

    private static final class Ribbon {
        final float[] x = new float[RIBBON_LEN];
        final float[] y = new float[RIBBON_LEN];
        final float[] z = new float[RIBBON_LEN];
        int n;
        float theta;
        float speed;
        float radius = 1.2f;
        float tilt;
        float life;
        boolean orbit;
    }

    private static final class Zzz {
        float x;
        float y;
        float life;
    }
}
