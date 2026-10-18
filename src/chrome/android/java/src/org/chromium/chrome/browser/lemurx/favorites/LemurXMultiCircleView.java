// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.favorites;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import org.chromium.build.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Folder tile icon: up to three round favicons overlapping left to right inside the tile (port
 * of Lemur's MultiCircleBitmapView). Bitmaps are drawn through a shader so no intermediate
 * bitmaps are allocated per frame.
 */
public class LemurXMultiCircleView extends View {
    private static final int MAX = 3;

    private final List<Bitmap> mBitmaps = new ArrayList<>();
    private final List<Integer> mFallbackColors = new ArrayList<>();
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final android.graphics.Matrix mMatrix = new android.graphics.Matrix();

    public LemurXMultiCircleView(Context context) {
        this(context, null);
    }

    public LemurXMultiCircleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    /** Replaces the icons; {@code null} entries draw as a solid circle of the fallback colour. */
    public void setIcons(List<Bitmap> bitmaps, List<Integer> fallbackColors) {
        mBitmaps.clear();
        mFallbackColors.clear();
        for (int i = 0; i < bitmaps.size() && i < MAX; i++) {
            mBitmaps.add(bitmaps.get(i));
            mFallbackColors.add(i < fallbackColors.size() ? fallbackColors.get(i) : 0xFFB3B3B3);
        }
        invalidate();
    }

    public void clearIcons() {
        mBitmaps.clear();
        mFallbackColors.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int count = mBitmaps.size();
        if (count == 0) return;
        int available = getWidth() - getPaddingStart() - getPaddingEnd();
        if (available <= 0) return;
        // Lemur: radius = width / (count + 1); circles step by one radius so they overlap by half.
        float radius = available / (float) (count + 1);
        float cy = getHeight() / 2f;
        for (int i = 0; i < count; i++) {
            float left = getPaddingStart() + radius * i;
            float cx = left + radius;
            Bitmap bmp = mBitmaps.get(i);
            if (bmp == null || bmp.isRecycled()) {
                mFillPaint.setColor(mFallbackColors.get(i));
                canvas.drawCircle(cx, cy, radius, mFillPaint);
                continue;
            }
            BitmapShader shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = (radius * 2f) / Math.min(bmp.getWidth(), bmp.getHeight());
            mMatrix.reset();
            mMatrix.setScale(scale, scale);
            mMatrix.postTranslate(
                    left - (bmp.getWidth() * scale - radius * 2f) / 2f,
                    cy - radius - (bmp.getHeight() * scale - radius * 2f) / 2f);
            shader.setLocalMatrix(mMatrix);
            mPaint.setShader(shader);
            mRect.set(left, cy - radius, left + radius * 2f, cy + radius);
            canvas.drawOval(mRect, mPaint);
        }
        mPaint.setShader(null);
    }
}
