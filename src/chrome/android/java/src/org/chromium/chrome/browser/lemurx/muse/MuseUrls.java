// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.text.TextUtils;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.url.GURL;

import java.util.UUID;

/**
 * LemurX 原生页面的地址。
 *
 * <p>控制台（对话 / 活动 / 数据）不再是网页，而是 Chromium 的 NativePage（和 chrome-native://newtab
 * 同一套机制）：地址栏、标签管理、历史、会话恢复都照常工作，页面本身是纯 Android 视图。
 *
 * <ul>
 *   <li>{@code chrome-native://lemurx/chat/<id>} 一个对话；每个标签页一个对话，id 就是服务端的
 *       会话 id（{@code /api/conversation?id=}、AG-UI 的 threadId）。
 *   <li>{@code chrome-native://lemurx/community} 社区：自愿加入的人，和他们公开留下的话。
 *   <li>{@code chrome-native://lemurx/tasks} 旧的任务地址，打开时落到社区。
 * </ul>
 *
 * <p>个人数据（密码 / 支付 / 地址 / 给智能体的资料）是全局的，不是页面：在 ☰ 设置里
 * （{@link LemurXDataFragment}）。
 */
@NullMarked
public final class MuseUrls {
    /** chrome-native 主机名。NativePage.java 里的 LEMURX 类型也认这个名字。 */
    public static final String HOST = "lemurx";

    public static final String SECTION_CHAT = "chat";
    public static final String SECTION_COMMUNITY = "community";
    /** 旧地址。打开时 {@link #sectionOf} 把它当成社区。 */
    public static final String SECTION_TASKS = "tasks";

    private static final String PREFIX = "chrome-native://" + HOST + "/";

    public static final String COMMUNITY_URL = PREFIX + SECTION_COMMUNITY;
    public static final String TASKS_URL = COMMUNITY_URL;

    private MuseUrls() {}

    /** 一个全新对话的地址（新标签页用）。 */
    public static String newChatUrl() {
        return chatUrl(UUID.randomUUID().toString());
    }

    public static String chatUrl(String threadId) {
        return PREFIX + SECTION_CHAT + "/" + threadId;
    }

    /** 是不是 LemurX 的原生页面地址。 */
    public static boolean isMuse(@Nullable String url) {
        return sectionOf(url) != null;
    }

    /** chat / community。旧的 tasks 地址也算社区。不是 LemurX 页面返回 null。 */
    public static @Nullable String sectionOf(@Nullable String url) {
        if (TextUtils.isEmpty(url) || !url.startsWith(PREFIX)) return null;
        String rest = url.substring(PREFIX.length());
        int cut = rest.indexOf('/');
        if (cut >= 0) rest = rest.substring(0, cut);
        cut = rest.indexOf('?');
        if (cut >= 0) rest = rest.substring(0, cut);
        cut = rest.indexOf('#');
        if (cut >= 0) rest = rest.substring(0, cut);
        switch (rest) {
            case SECTION_CHAT:
                return rest;
            case SECTION_COMMUNITY:
            case SECTION_TASKS:
                return SECTION_COMMUNITY;
            default:
                return null;
        }
    }

    /** 对话地址里的会话 id；不是对话地址返回 null。 */
    public static @Nullable String threadOf(@Nullable String url) {
        if (!SECTION_CHAT.equals(sectionOf(url))) return null;
        String rest = url.substring(PREFIX.length() + SECTION_CHAT.length());
        if (!rest.startsWith("/")) return null;
        rest = rest.substring(1);
        int cut = rest.indexOf('?');
        if (cut >= 0) rest = rest.substring(0, cut);
        cut = rest.indexOf('#');
        if (cut >= 0) rest = rest.substring(0, cut);
        while (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        return rest.matches("[A-Za-z0-9-]{1,64}") ? rest : null;
    }

    public static @Nullable String threadOf(@Nullable GURL url) {
        return url == null ? null : threadOf(url.getSpec());
    }
}
