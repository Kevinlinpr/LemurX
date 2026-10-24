// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.agent;

import android.graphics.RectF;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.Nullable;
import org.chromium.content_public.browser.RenderCoordinates;
import org.chromium.content_public.browser.WebContents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 网页快照：从无障碍树（{@link LemurXAx}）读到的文本和可操作元素，再用 RenderCoordinates
 * 把每个元素的 CSS 矩形换算到标签视图的物理像素。
 *
 * <p>这是 agent "看"网页的唯一通道——不注入脚本、不开 DevTools，页面感知不到任何读取。
 * 读到的全部内容（名字、值、文本）都是不可信数据，原样交给服务端，这里只做几何换算与匹配。
 *
 * <p>坐标：AX 树给的矩形是"根框架坐标"（已减去各容器自身的滚动；Android 上 Blink 的布局单位
 * 已含设备像素比）；换到屏幕要乘页面缩放，再减去视口滚动的物理像素、加上网页在视图里的顶部
 * 偏移（工具栏）。
 */
final class LemurXPage {
    private static final String TAG = "LemurXAgent";
    private static final long SNAPSHOT_TIMEOUT_MS = 4_000;

    /** 一个可操作元素；坐标已是标签视图的物理像素。 */
    static final class Element {
        int id;
        String role = "";
        String name = "";
        String value = "";
        String placeholder = "";
        String url = "";
        String tag = "";
        String inputType = "";
        /** 标签视图物理像素（含工具栏偏移）。 */
        final RectF px = new RectF();
        boolean offscreen;
        boolean editable;
        boolean focused;
        boolean disabled;
        @Nullable String checked;
        @Nullable Boolean expanded;
        @Nullable Boolean selected;

        float cx() {
            return px.centerX();
        }

        float cy() {
            return px.centerY();
        }

        /** 用于匹配的全部文字（小写）。 */
        String haystack() {
            return (name + " " + value + " " + placeholder + " " + url).toLowerCase(Locale.ROOT);
        }

        /** 给服务端看的描述：和屏幕截图同一套 1280x800 虚拟画布的坐标。 */
        JSONObject toJson(Snapshot s) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("role", role);
                if (!name.isEmpty()) o.put("name", name);
                // 密码框的内容留在本机，不跟元素清单回服务端。
                if (!value.isEmpty() && !"password".equalsIgnoreCase(inputType)) {
                    o.put("value", value);
                }
                if (!placeholder.isEmpty()) o.put("placeholder", placeholder);
                if (!url.isEmpty()) o.put("url", url);
                if (!tag.isEmpty()) o.put("tag", tag);
                if (!inputType.isEmpty()) o.put("inputType", inputType);
                if (editable) o.put("editable", true);
                if (focused) o.put("focused", true);
                if (disabled) o.put("disabled", true);
                if (checked != null) o.put("checked", "mixed".equals(checked) ? "mixed" : Boolean.parseBoolean(checked));
                if (expanded != null) o.put("expanded", expanded);
                if (selected != null) o.put("selected", selected);
                // 1280x800 画布坐标（和 /screenshot、click{x,y} 一致），四舍五入到整数。
                double sx = 1280.0 / s.viewportW;
                double sy = 800.0 / s.viewportH;
                JSONArray rect = new JSONArray();
                rect.put(Math.round(px.left * sx));
                rect.put(Math.round((px.top - s.contentOffsetY) * sy));
                rect.put(Math.round(px.width() * sx));
                rect.put(Math.round(px.height() * sy));
                o.put("rect", rect);
                o.put("visible", s.visible(this));
            } catch (Exception ignored) {
                // 键是常量
            }
            return o;
        }
    }

    /** 一次快照：文本 + 元素 + 当时的视口几何。 */
    static final class Snapshot {
        String url = "";
        String title = "";
        String text = "";
        boolean truncated;
        int focusId = -1;
        final List<Element> elements = new ArrayList<>();
        // 视口几何（物理像素）
        int viewportW;
        int viewportH;
        int contentOffsetY;
        float pageScale = 1;
        float minPageScale = 1;
        float dsf = 1;
        int scrollXPix;
        int scrollYPix;

        /** 元素和视口是否有可点的重叠（粘性顶栏的按钮经常有几像素探出视口外）。 */
        boolean visible(Element e) {
            return visibleHeight(e) >= Math.min(e.px.height(), 8 * dsf)
                    && visibleWidth(e) >= Math.min(e.px.width(), 8 * dsf);
        }

        float visibleHeight(Element e) {
            if (e.px.height() <= 0) return 0;
            return Math.max(0, Math.min(e.px.bottom, contentOffsetY + viewportH) - Math.max(e.px.top, contentOffsetY));
        }

        float visibleWidth(Element e) {
            if (e.px.width() <= 0) return 0;
            return Math.max(0, Math.min(e.px.right, viewportW) - Math.max(e.px.left, 0));
        }

        /** 落指点：矩形中心夹进视口内，避免点到屏幕外。 */
        float tapX(Element e) {
            float pad = 8 * dsf;
            return Math.max(pad, Math.min(viewportW - pad, e.cx()));
        }

        float tapY(Element e) {
            float pad = 8 * dsf;
            return Math.max(contentOffsetY + pad, Math.min(contentOffsetY + viewportH - pad, e.cy()));
        }

        /** 元素相对视口要滚多少物理像素才能到视口中部（0 表示已可见）。 */
        float scrollNeededY(Element e) {
            float top = contentOffsetY;
            float bottom = contentOffsetY + viewportH;
            // 已经有足够一截露在视口里（粘性顶栏、半截进屏）就不必再滚。
            if (visibleHeight(e) >= Math.min(e.px.height(), 18 * dsf)
                    && visibleWidth(e) >= Math.min(e.px.width(), 18 * dsf)) {
                return 0;
            }
            float pad = viewportH * 0.15f;
            if (e.px.top >= top + pad && e.px.bottom <= bottom - pad) return 0;
            // 目标：把元素中心放到视口 45% 处。
            return e.cy() - (top + viewportH * 0.45f);
        }

        /**
         * 横向同理（页面放大后元素可能跑到视口左右之外）。只有中心点真出了视口才算需要，
         * 贴着左右边排版的元素很常见，不能因此去横着滚。
         */
        float scrollNeededX(Element e) {
            float m = 6 * dsf;
            if (e.cx() >= m && e.cx() <= viewportW - m) return 0;
            return e.cx() - viewportW / 2f;
        }

        @Nullable Element byId(int id) {
            for (Element e : elements) if (e.id == id) return e;
            return null;
        }

        @Nullable Element focused() {
            return focusId < 0 ? null : byId(focusId);
        }

        JSONArray elementsJson() {
            JSONArray out = new JSONArray();
            for (Element e : elements) out.put(e.toJson(this));
            return out;
        }
    }

    private LemurXPage() {}

    /**
     * 抓一次快照（后台线程调用；内部切 UI 线程请求 AX 树并在回调里读视口几何）。
     * 失败 / 超时返回 null。
     */
    static @Nullable Snapshot capture(WebContents wc, View view, int maxNodes) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Snapshot> out = new AtomicReference<>();
        ThreadUtils.postOnUiThread(() -> {
            try {
                LemurXAx.snapshot(wc, maxNodes, json -> {
                    try {
                        if (json != null && json.optBoolean("ok", false)) {
                            out.set(parse(json, wc, view));
                        } else if (json != null) {
                            Log.i(TAG, "ax snapshot unavailable: nodes=%d %s",
                                    json.optInt("nodes", -1), json.optString("error", ""));
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "ax parse", t);
                    } finally {
                        latch.countDown();
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "ax request", t);
                latch.countDown();
            }
        });
        try {
            if (!latch.await(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return out.get();
    }

    /** UI 线程：把 JNI 回来的 JSON 变成带物理像素坐标的快照。 */
    private static Snapshot parse(JSONObject json, WebContents wc, View view) {
        Snapshot s = new Snapshot();
        s.url = json.optString("url", "");
        s.title = json.optString("title", "");
        s.text = json.optString("text", "");
        s.truncated = json.optBoolean("truncated", false);
        s.focusId = json.optInt("focusId", -1);
        s.dsf = view.getResources().getDisplayMetrics().density;
        s.viewportW = view.getWidth();
        s.viewportH = view.getHeight();
        try {
            RenderCoordinates rc = RenderCoordinates.fromWebContents(wc);
            if (rc.getLastFrameViewportWidthPixInt() > 0) {
                s.viewportW = rc.getLastFrameViewportWidthPixInt();
            }
            if (rc.getLastFrameViewportHeightPixInt() > 0) {
                s.viewportH = rc.getLastFrameViewportHeightPixInt();
            }
            s.contentOffsetY = rc.getContentOffsetYPixInt();
            s.pageScale = rc.getPageScaleFactor();
            s.minPageScale = rc.getMinPageScaleFactor();
            s.scrollXPix = rc.getScrollXPixInt();
            s.scrollYPix = rc.getScrollYPixInt();
        } catch (Throwable t) {
            Log.i(TAG, "render coords: %s", t.toString());
        }
        // Android 的 Blink 把设备像素比烘进了布局（zoom-for-DSF）：AX 矩形在页面缩放为 1 时就已经是
        // 物理像素，所以这里只乘页面缩放。
        float k = s.pageScale;
        // AX 树给的矩形已经减掉了根节点（文档）的滚动量（AXTree::RelativeToTreeBounds 会按容器的
        // kScrollX/kScrollY 偏移），所以它们是"布局视口"坐标。RenderCoordinates 的滚动像素是
        // 文档滚动 + 双指放大后的视觉视口平移之和；只需再减掉后者那部分。
        float panX = s.scrollXPix - json.optInt("scrollX", 0) * k;
        float panY = s.scrollYPix - json.optInt("scrollY", 0) * k;
        JSONArray arr = json.optJSONArray("elements");
        if (arr == null) return s;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Element e = new Element();
            e.id = o.optInt("id", -1);
            e.role = o.optString("role", "");
            // 下拉框里的选项不是屏幕上能点的东西：弹层打开后由 dialog 报告和回答。
            if ("menuListOption".equals(e.role)) continue;
            e.name = o.optString("name", "");
            e.value = o.optString("value", "");
            e.placeholder = o.optString("placeholder", "");
            e.url = o.optString("url", "");
            e.tag = o.optString("tag", "");
            e.inputType = o.optString("inputType", "");
            e.offscreen = o.optBoolean("offscreen", false);
            e.editable = o.optBoolean("editable", false);
            e.focused = o.optBoolean("focused", false);
            e.disabled = o.optBoolean("disabled", false);
            if (o.has("checked")) e.checked = String.valueOf(o.opt("checked"));
            if (o.has("expanded")) e.expanded = o.optBoolean("expanded");
            if (o.has("selected")) e.selected = o.optBoolean("selected");
            JSONArray r = o.optJSONArray("rect");
            if (r != null && r.length() == 4) {
                // 布局视口坐标 → 视口物理像素：×页面缩放 − 视觉视口平移 + 顶部偏移。
                float left = (float) (r.optDouble(0) * k) - panX;
                float top = (float) (r.optDouble(1) * k) - panY + s.contentOffsetY;
                e.px.set(left, top, left + (float) (r.optDouble(2) * k),
                        top + (float) (r.optDouble(3) * k));
            }
            s.elements.add(e);
        }
        return s;
    }

    // ---------------------------------------------------------------- 定位

    /**
     * 解析服务端给的 target：整数 = 元素 id；字符串 = 可见文字；对象 = {id, text, role, index}。
     * 先按 id，再按文字（完全相等 > 前缀 > 包含；可按 role 过滤；index 取第几个）。找不到返回 null。
     */
    static @Nullable Element resolve(Snapshot s, Object target) {
        if (target == null || target == JSONObject.NULL) return null;
        int id = -1;
        String text = null;
        String role = null;
        int index = 0;
        if (target instanceof Number) {
            id = ((Number) target).intValue();
        } else if (target instanceof String) {
            String str = (String) target;
            if (str.matches("^\\d+$")) {
                id = Integer.parseInt(str);
            } else {
                text = str;
            }
        } else if (target instanceof JSONObject) {
            JSONObject o = (JSONObject) target;
            id = o.optInt("id", -1);
            text = o.has("text") ? o.optString("text") : null;
            role = o.has("role") ? o.optString("role") : null;
            index = Math.max(0, o.optInt("index", 0));
        }
        if (id >= 0) {
            Element e = s.byId(id);
            if (e != null) return e;
            if (text == null && (role == null || role.isEmpty())) return null;
        }
        if (text == null || text.trim().isEmpty()) {
            // 只给了 role：第一个匹配的（可见优先）。
            if (role == null || role.isEmpty()) return null;
            List<Element> byRole = new ArrayList<>();
            for (Element e : s.elements) {
                if (matchesRole(e, role)) byRole.add(e);
            }
            return pick(s, byRole, index);
        }
        String needle = text.trim().toLowerCase(Locale.ROOT);
        List<Element> exact = new ArrayList<>();
        List<Element> prefix = new ArrayList<>();
        List<Element> contains = new ArrayList<>();
        for (Element e : s.elements) {
            if (role != null && !role.isEmpty() && !matchesRole(e, role)) continue;
            String hay = e.haystack();
            String name = e.name.trim().toLowerCase(Locale.ROOT);
            String value = e.value.trim().toLowerCase(Locale.ROOT);
            String ph = e.placeholder.trim().toLowerCase(Locale.ROOT);
            if (needle.equals(name) || needle.equals(value) || needle.equals(ph)) exact.add(e);
            else if (name.startsWith(needle) || ph.startsWith(needle)) prefix.add(e);
            else if (hay.contains(needle)) contains.add(e);
        }
        List<Element> ranked = new ArrayList<>(exact);
        ranked.addAll(prefix);
        ranked.addAll(contains);
        return pick(s, ranked, index);
    }

    private static @Nullable Element pick(Snapshot s, List<Element> ranked, int index) {
        if (ranked.isEmpty()) return null;
        // 同一名次里可见的优先。
        List<Element> ordered = new ArrayList<>();
        for (Element e : ranked) if (s.visible(e)) ordered.add(e);
        for (Element e : ranked) if (!s.visible(e)) ordered.add(e);
        return index < ordered.size() ? ordered.get(index) : null;
    }

    private static boolean matchesRole(Element e, String role) {
        String want = role.toLowerCase(Locale.ROOT);
        String have = e.role.toLowerCase(Locale.ROOT);
        if (have.equals(want)) return true;
        // 常见别名
        switch (want) {
            case "input":
            case "textbox":
            case "textfield":
            case "search":
            case "searchbox":
                return e.editable || have.contains("textfield") || have.contains("searchbox")
                        || have.contains("combobox");
            case "button":
                return have.contains("button");
            case "link":
                return have.equals("link");
            case "checkbox":
                return have.contains("checkbox") || have.contains("switch");
            case "radio":
                return have.contains("radiobutton");
            case "select":
            case "combobox":
                return have.contains("combobox") || have.contains("listbox")
                        || have.contains("popupbutton");
            case "heading":
                return have.equals("heading");
            case "image":
            case "img":
                return have.equals("image");
            default:
                return have.contains(want);
        }
    }

    /** 元素的简短描述（回包给服务端）。 */
    static JSONObject brief(Element e) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", e.id);
            o.put("role", e.role);
            if (!e.name.isEmpty()) o.put("name", e.name.length() > 120 ? e.name.substring(0, 120) : e.name);
        } catch (Exception ignored) {
            // 键是常量
        }
        return o;
    }
}
