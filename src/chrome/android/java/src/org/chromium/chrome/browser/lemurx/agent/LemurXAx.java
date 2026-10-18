// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.agent;

import org.jni_zero.JniType;
import org.jni_zero.NativeMethods;
import org.json.JSONObject;

import org.chromium.base.Callback;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.content_public.browser.WebContents;

/**
 * 无障碍树快照（Java 侧）。
 *
 * <p>Agent 读页面正文、找元素都走这里：渲染进程一次性序列化它自己的无障碍对象缓存，
 * 不往页面注入脚本、不挂 DevTools，页面侧没有任何可观察的痕迹。截图（{@link #capture}）
 * 同样不碰渲染进程。原生实现见 chrome/browser/ui/android/lemurx/lemurx_ax.cc。
 *
 * <p>回包：{@code {ok, url, title, focusId, scrollX, scrollY, rootRect, text, truncated,
 * elements:[{id, role, name, value, placeholder, url, tag, inputType, rect:[x,y,w,h] (CSS
 * 像素、相对根框架), offscreen, editable, focused, disabled, expanded, selected, checked}]}}。
 */
@NullMarked
public final class LemurXAx {
    private LemurXAx() {}

    /** 拍一张快照。UI 线程发起，UI 线程回调。 */
    public static void snapshot(WebContents webContents, int maxNodes, Callback<JSONObject> callback) {
        ThreadUtils.assertOnUiThread();
        LemurXAxJni.get().snapshot(webContents, maxNodes, (String raw) -> {
            JSONObject parsed;
            try {
                parsed = new JSONObject(raw);
            } catch (Exception e) {
                parsed = new JSONObject();
            }
            callback.onResult(parsed);
        });
    }

    /**
     * 网页视口截图：合成器拷贝输出 → JPEG base64（拿不到为空串）。不经过渲染进程，页面无感；
     * 不含 agent 自己的手指 / 键盘 / 横幅等 Android 视图。UI 线程发起，UI 线程回调。
     */
    public static void capture(WebContents webContents, int quality, Callback<String> callback) {
        ThreadUtils.assertOnUiThread();
        LemurXAxJni.get().capture(webContents, quality, callback);
    }

    @NativeMethods
    interface Natives {
        void snapshot(
                @JniType("content::WebContents*") WebContents webContents,
                int maxNodes,
                Callback<String> callback);

        void capture(
                @JniType("content::WebContents*") WebContents webContents,
                int quality,
                Callback<String> callback);
    }
}
