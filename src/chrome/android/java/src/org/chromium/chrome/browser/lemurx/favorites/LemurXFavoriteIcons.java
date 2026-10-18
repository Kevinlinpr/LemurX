// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.favorites;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.LruCache;

import org.chromium.base.Callback;
import org.chromium.base.Log;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.ui.favicon.FaviconUtils;
import org.chromium.components.browser_ui.widget.RoundedIconGenerator;
import org.chromium.components.favicon.LargeIconBridge;
import org.chromium.url.GURL;

/**
 * Favicon lookup for the favorites grid (Lemur: FavoriteBeanDao.getIconFromNative). Returns a
 * square bitmap at tile size: the site's large icon when Chromium has one, otherwise a coloured
 * circle with the first letter of the host. Results are cached per URL.
 */
class LemurXFavoriteIcons {
    private static final String TAG = "LemurXFavIcons";

    private final Context mContext;
    private final int mSizePx;
    private final LruCache<String, Bitmap> mCache = new LruCache<>(64);
    private final RoundedIconGenerator mFallbackGenerator;
    private @Nullable LargeIconBridge mBridge;
    private boolean mDestroyed;

    LemurXFavoriteIcons(Context context, Profile profile, int sizePx) {
        mContext = context;
        mSizePx = sizePx;
        mFallbackGenerator = FaviconUtils.createCircularIconGenerator(context);
        try {
            mBridge = new LargeIconBridge(profile);
        } catch (Throwable t) {
            Log.w(TAG, "LargeIconBridge unavailable: %s", t.toString());
            mBridge = null;
        }
    }

    void destroy() {
        mDestroyed = true;
        if (mBridge != null) {
            mBridge.destroy();
            mBridge = null;
        }
        mCache.evictAll();
    }

    @Nullable Bitmap cached(String url) {
        return mCache.get(url);
    }

    /**
     * Fetches the icon for {@code url}. {@code callback} runs on the UI thread, possibly
     * synchronously when the icon is cached.
     */
    void get(String url, Callback<Bitmap> callback) {
        Bitmap hit = mCache.get(url);
        if (hit != null) {
            callback.onResult(hit);
            return;
        }
        GURL gurl = new GURL(url);
        if (mBridge == null || !gurl.isValid()) {
            Bitmap fallback = fallbackFor(gurl, url, 0);
            mCache.put(url, fallback);
            callback.onResult(fallback);
            return;
        }
        boolean expected;
        try {
            expected =
                    mBridge.getLargeIconForUrl(
                            gurl,
                            Math.max(16, mSizePx / 4),
                            mSizePx,
                            (icon, fallbackColor, isFallbackColorDefault, iconType) -> {
                                if (mDestroyed) return;
                                Bitmap result =
                                        icon != null
                                                ? centerOnCircle(icon, fallbackColor)
                                                : fallbackFor(gurl, url, fallbackColor);
                                mCache.put(url, result);
                                callback.onResult(result);
                            });
        } catch (Throwable t) {
            Log.w(TAG, "getLargeIconForUrl failed: %s", t.toString());
            expected = false;
        }
        if (!expected) {
            Bitmap fallback = fallbackFor(gurl, url, 0);
            mCache.put(url, fallback);
            callback.onResult(fallback);
        }
    }

    private Bitmap fallbackFor(GURL gurl, String url, int fallbackColor) {
        if (fallbackColor != 0) {
            mFallbackGenerator.setBackgroundColor(fallbackColor);
        } else {
            mFallbackGenerator.setBackgroundColor(0xFF8A8A8E);
        }
        Bitmap bmp = null;
        try {
            bmp = gurl.isValid() ? mFallbackGenerator.generateIconForUrl(gurl) : null;
            if (bmp == null) {
                String host = LemurXFavorite.hostOf(url);
                bmp = mFallbackGenerator.generateIconForText(host.isEmpty() ? "?" : host);
            }
        } catch (Throwable t) {
            Log.w(TAG, "fallback icon failed: %s", t.toString());
        }
        if (bmp == null) {
            bmp = Bitmap.createBitmap(mSizePx, mSizePx, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(0xFF8A8A8E);
        }
        return bmp;
    }

    /**
     * Favicons are usually 16..48px, often with transparent corners. Draw them centred on a
     * filled circle so the round tile looks like Lemur's fitXY icon instead of a tiny glyph.
     */
    private Bitmap centerOnCircle(Bitmap icon, int fallbackColor) {
        if (icon.getWidth() >= mSizePx && icon.getHeight() >= mSizePx) return icon;
        Bitmap out = Bitmap.createBitmap(mSizePx, mSizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int bg = fallbackColor != 0 ? lighten(fallbackColor) : 0xFFF2F2F2;
        paint.setColor(bg);
        canvas.drawCircle(mSizePx / 2f, mSizePx / 2f, mSizePx / 2f, paint);
        // Icon occupies ~60% of the circle, like Lemur's padded shortcut icons.
        int inner = Math.round(mSizePx * 0.6f);
        int left = (mSizePx - inner) / 2;
        Rect dst = new Rect(left, left, left + inner, left + inner);
        canvas.drawBitmap(icon, null, dst, paint);
        return out;
    }

    private static int lighten(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] *= 0.35f;
        hsv[2] = Math.max(hsv[2], 0.92f);
        return Color.HSVToColor(hsv);
    }
}
