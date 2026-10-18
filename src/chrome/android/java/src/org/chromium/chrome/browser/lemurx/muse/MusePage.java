// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.chromium.base.Log;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.lemurx.LemurXConsoleShell;
import org.chromium.chrome.browser.lemurx.LemurXHaptics;
import org.chromium.chrome.browser.settings.SettingsNavigationFactory;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.ui.native_page.BasicSmoothTransitionDelegate;
import org.chromium.chrome.browser.ui.native_page.NativePage;
import org.chromium.chrome.browser.ui.native_page.NativePageHost;
import org.chromium.content_public.browser.LoadUrlParams;

import java.util.HashMap;
import java.util.Map;

/**
 * LemurX 控制台的 NativePage：{@code chrome-native://lemurx/…}。
 *
 * <p>同一个标签页里 chat / tasks 共用这一个页面对象（NativePageFactory 对同主机地址走 CANDIDATE
 * 复用，只调 {@link #updateForUrl}），所以切分区不重建视图，对话的滚动位置、正在进行的回复都原样
 * 保留。标签页的会话 id 记在 {@link LemurXConsoleShell#tabThread}（按标签页 id），所以从对话切到
 * 网页再切回来，也还是同一轮对话。
 *
 * <p>直接实现 {@link NativePage} 而不是继承 BasicNativePage：后者会跟着工具栏的显隐动画改视图
 * 外边距，新标签页打开时工具栏从显示收到隐藏，页面就跟着"从小变大"一下。外壳把工具栏常藏着，
 * 这里的视图始终铺满，自己处理状态栏内边距和底部导航药丸的高度。
 */
@NullMarked
public class MusePage implements NativePage {
    private static final String TAG = "LemurXMuse";

    static final int COLOR_TEXT = 0xFF132631;
    static final int COLOR_MUTED = 0xFF6B7680;
    static final int COLOR_SURFACE = 0xFFF0F1F2;
    static final int COLOR_BG = 0xFFFFFFFF;
    static final int COLOR_LINE = 0xFFE6E8EA;
    static final int COLOR_ACCENT = 0xFF132631;

    /** 页面之外的事：在新标签页里打开链接。原生页面模式走 NativePageHost，覆盖层模式由外壳提供。 */
    public interface Links {
        void openInNewTab(String url);
    }

    private final Tab mTab;
    private final Links mLinks;

    /** 原生页面模式下的宿主；覆盖层模式（画在活着的网页上面）为 null。 */
    private final @Nullable NativePageHost mHost;

    private final FrameLayout mRoot;
    private final FrameLayout mContent;
    private final TextView mHeading;
    private final View mNewChat;

    /**
     * 标签页 id → 这个标签页的对话视图。原生页面在标签页离开 chrome-native:// 时就会被销毁、
     * 回来时重建，但一个标签页就是一个 Agent，它的会话状态（消息、正在流式输出的回复、
     * 没发出去的草稿、滚动位置）要跟着标签页活，而不是跟着页面活：这里把视图缓存起来，
     * 页面重建时直接挂回去，标签页关掉（{@link #onTabDestroyed}）或活动页销毁
     * （{@link #destroyAll}）时才真正销毁。
     */
    private static final Map<Integer, MuseChatView> sChats = new HashMap<>();

    private @Nullable MuseChatView mChat;
    private @Nullable MuseTasksView mTasks;

    private String mUrl = "";
    private String mSection = MuseUrls.SECTION_CHAT;
    /** 这个标签页的会话 id；还没进过对话分区时为 null。 */
    private @Nullable String mThreadId;

    private final Runnable mNavListener = this::applyNavPadding;

    /**
     * 预测式返回手势回到这个页面时 TabImpl 会调 enableSmoothTransition() 并直接用返回值
     * （assumeNonNull），默认实现返回 null 会让整个浏览器进程 NPE 崩掉，所以必须给一个。
     */
    private @Nullable BasicSmoothTransitionDelegate mSmoothTransition;
    private int mStatusInset;
    private int mNavPadding;

    /** 原生页面模式：标签页本身停在 chrome-native://lemurx/… 上（新标签页、恢复的会话标签页）。 */
    public MusePage(Tab tab, NativePageHost host, String url) {
        this(tab, host.getContext(), host, link -> host.openNewTab(new LoadUrlParams(link)), url);
    }

    /**
     * 覆盖层模式：标签页停在网页上，页面画在网页上面，网页不导航、不刷新。换分区 / 新对话都只改
     * 自己的内容，不碰标签页历史。
     */
    public static MusePage overlay(Tab tab, Context context, Links links, String url) {
        return new MusePage(tab, context, null, links, url);
    }

    private MusePage(
            Tab tab, Context context, @Nullable NativePageHost host, Links links, String url) {
        mTab = tab;
        mHost = host;
        mLinks = links;
        mRoot =
                new FrameLayout(context) {
                    @Override
                    protected void onLayout(boolean changed, int l, int t, int r, int b) {
                        super.onLayout(changed, l, t, r, b);
                        updateStatusInset();
                    }
                };
        mRoot.setBackgroundColor(COLOR_BG);
        mRoot.setClickable(true); // 不让触摸漏到下面的 WebContents

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        // ---- 顶栏
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(6), dp(8), dp(6));
        ImageView menu = iconButton(context, R.drawable.lemurx_muse_ic_menu, R.string.lemurx_muse_settings);
        menu.setOnClickListener(
                v -> {
                    LemurXHaptics.click(v);
                    openSettings();
                });
        header.addView(menu);

        ImageView logo = new ImageView(context);
        logo.setImageResource(R.mipmap.app_icon);
        logo.setClipToOutline(true);
        logo.setOutlineProvider(
                new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getWidth() * 0.26f);
                    }
                });
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(26), dp(26));
        logoLp.setMarginStart(dp(4));
        logoLp.setMarginEnd(dp(8));
        header.addView(logo, logoLp);

        mHeading = new TextView(context);
        mHeading.setTextColor(COLOR_TEXT);
        mHeading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        mHeading.setTypeface(Typeface.DEFAULT_BOLD);
        mHeading.setText(R.string.lemurx_muse_brand);
        header.addView(mHeading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mNewChat = iconButton(context, R.drawable.lemurx_muse_ic_plus, R.string.lemurx_muse_new_chat);
        mNewChat.setOnClickListener(
                v -> {
                    LemurXHaptics.tick(v);
                    newChat();
                });
        header.addView(mNewChat);
        column.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mContent = new FrameLayout(context);
        column.addView(mContent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mRoot.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mRoot.setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    updateStatusInset();
                    return insets;
                });

        // 第一帧就把内边距摆对，不等布局完再补一次（那样会闪一下）。
        mStatusInset = LemurXConsoleShell.statusInsetPx();
        mNavPadding = LemurXConsoleShell.navHeightPx();
        applyPadding();
        LemurXConsoleShell.addNavListener(mNavListener);
        updateForUrl(url);
    }

    static ImageView iconButton(Context context, int icon, int description) {
        ImageView button = new ImageView(context);
        button.setImageResource(icon);
        button.setContentDescription(context.getString(description));
        button.setBackgroundResource(R.drawable.lemurx_console_cell_bg);
        button.setClickable(true);
        button.setFocusable(true);
        int pad = dp(context, 10);
        button.setPadding(pad, pad, pad, pad);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)));
        return button;
    }

    private int dp(int value) {
        return dp(mRoot.getContext(), value);
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- NativePage

    @Override
    public View getView() {
        return mRoot;
    }

    @Override
    public String getUrl() {
        return mUrl;
    }

    @Override
    public int getBackgroundColor() {
        return COLOR_BG;
    }

    @Override
    public boolean needsToolbarShadow() {
        return false;
    }

    @Override
    public SmoothTransitionDelegate enableSmoothTransition() {
        if (mSmoothTransition == null) mSmoothTransition = new BasicSmoothTransitionDelegate(mRoot);
        return mSmoothTransition;
    }

    @Override
    public void updateForUrl(String url) {
        mUrl = url;
        String section = MuseUrls.sectionOf(url);
        if (section == null) section = MuseUrls.SECTION_CHAT;
        String thread = MuseUrls.threadOf(url);
        if (MuseUrls.SECTION_CHAT.equals(section)) {
            if (thread == null) thread = mThreadId;
            if (thread == null) thread = LemurXConsoleShell.tabThread(mTab);
            if (thread == null) thread = MuseUrls.threadOf(MuseUrls.newChatUrl());
            mThreadId = thread;
            LemurXConsoleShell.setTabThread(mTab, thread);
        }
        show(section);
    }

    private void show(String section) {
        mSection = section;
        if (mChat != null) mChat.setVisibility(View.GONE);
        if (mTasks != null) mTasks.setVisibility(View.GONE);
        Context context = mRoot.getContext();
        if (MuseUrls.SECTION_TASKS.equals(section)) {
            if (mTasks == null) {
                mTasks = new MuseTasksView(context, this);
                mContent.addView(mTasks, fill());
            }
            mTasks.setVisibility(View.VISIBLE);
            mTasks.refreshNow();
            mHeading.setText(R.string.lemurx_muse_tasks);
            mNewChat.setVisibility(View.INVISIBLE);
        } else {
            if (mChat == null) {
                mChat = obtainChat(context);
                mContent.addView(mChat, fill());
            }
            mChat.setVisibility(View.VISIBLE);
            if (mThreadId != null) mChat.load(mThreadId);
            mHeading.setText(R.string.lemurx_muse_brand);
            mNewChat.setVisibility(View.VISIBLE);
        }
        try {
            mTab.updateTitle();
        } catch (Exception e) {
            Log.i(TAG, "updateTitle: %s", e.getMessage());
        }
    }

    /** 取这个标签页缓存的对话视图（换了 Activity 的旧视图不能复用，销毁重建）。 */
    private MuseChatView obtainChat(Context context) {
        int tabId = mTab.getId();
        MuseChatView chat = sChats.get(tabId);
        if (chat != null && chat.getContext() != context) {
            chat.destroy();
            sChats.remove(tabId);
            chat = null;
        }
        if (chat == null) {
            chat = new MuseChatView(context, this);
            sChats.put(tabId, chat);
        } else {
            chat.attachPage(this);
            if (chat.getParent() instanceof ViewGroup) {
                ((ViewGroup) chat.getParent()).removeView(chat);
            }
        }
        return chat;
    }

    /** 标签页关掉了：它的对话视图跟着销毁。 */
    public static void onTabDestroyed(int tabId) {
        MuseChatView chat = sChats.remove(tabId);
        if (chat != null) chat.destroy();
    }

    /** 活动页销毁：清掉所有缓存的对话视图。 */
    public static void destroyAll() {
        for (MuseChatView chat : sChats.values()) chat.destroy();
        sChats.clear();
    }

    private static FrameLayout.LayoutParams fill() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    @Override
    public String getTitle() {
        Resources res = mRoot.getResources();
        if (MuseUrls.SECTION_TASKS.equals(mSection)) return res.getString(R.string.lemurx_muse_tasks);
        String title = mChat != null ? mChat.title() : null;
        return title != null ? title : res.getString(R.string.lemurx_muse_brand);
    }

    @Override
    public String getHost() {
        return MuseUrls.HOST;
    }

    @Override
    public void destroy() {
        LemurXConsoleShell.removeNavListener(mNavListener);
        // 对话视图不销毁：从页面上摘下来留给这个标签页下一次的 MusePage，见 sChats。
        if (mChat != null) {
            mContent.removeView(mChat);
            mChat.detachPage(this);
            mChat = null;
        }
        if (mTasks != null) mTasks.destroy();
    }

    // ---------------------------------------------------------------- 给分区视图 / 外壳用

    Tab tab() {
        return mTab;
    }

    /** 是不是画在网页上面的覆盖层（而不是标签页自己的原生页面）。 */
    public boolean isOverlay() {
        return mHost == null;
    }

    /** 当前分区。 */
    public String section() {
        return mSection;
    }

    /** 这个标签页的会话 id（没进过对话分区则 null）。 */
    public @Nullable String threadId() {
        return mThreadId;
    }

    /** 回到这个标签页的对话：有会话就回它，没有就开新的。 */
    public String chatUrl() {
        return mThreadId != null ? MuseUrls.chatUrl(mThreadId) : MuseUrls.newChatUrl();
    }

    /** 对话标题变了（第一条消息发出）：让标签页标题跟上。 */
    void notifyTitleChanged() {
        try {
            mTab.updateTitle();
        } catch (Exception e) {
            Log.i(TAG, "updateTitle: %s", e.getMessage());
        }
    }

    /** 在新标签页里打开一个地址（模型回复里的链接等）。 */
    void openInNewTab(String url) {
        try {
            mLinks.openInNewTab(url);
        } catch (Exception e) {
            Log.i(TAG, "openNewTab: %s", e.getMessage());
        }
    }

    /** 顶栏「新对话」：本标签页里开一轮新会话（新历史条目，返回能回到上一轮）。 */
    private void newChat() {
        String url = MuseUrls.newChatUrl();
        if (url.equals(mUrl)) return;
        if (mHost == null) {
            // 覆盖层：换会话只换自己的内容，网页不动。
            updateForUrl(url);
            return;
        }
        mHost.loadUrl(new LoadUrlParams(url), /* incognito= */ false);
    }

    private void openSettings() {
        try {
            SettingsNavigationFactory.createSettingsNavigation()
                    .startSettings(mRoot.getContext(), LemurXSettingsFragment.class);
        } catch (Exception e) {
            Log.i(TAG, "settings: %s", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 内边距

    /** 页面顶到了窗口顶部（工具栏藏着）时给状态栏让位。 */
    private void updateStatusInset() {
        int inset = 0;
        try {
            int[] loc = new int[2];
            mRoot.getLocationInWindow(loc);
            if (loc[1] <= 0) {
                WindowInsets insets = mRoot.getRootWindowInsets();
                if (insets != null) {
                    inset = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            ? insets.getInsets(WindowInsets.Type.statusBars()).top
                            : insets.getSystemWindowInsetTop();
                }
            }
        } catch (Exception e) {
            inset = 0;
        }
        if (inset != mStatusInset) {
            mStatusInset = inset;
            mRoot.post(this::applyPadding);
        }
    }

    private void applyNavPadding() {
        int nav = LemurXConsoleShell.navHeightPx();
        if (nav != mNavPadding) {
            mNavPadding = nav;
            applyPadding();
        }
    }

    private void applyPadding() {
        mRoot.setPadding(0, mStatusInset, 0, mNavPadding);
    }

    /** 分区视图在底部需要留的空间以外，额外统一的内容边距。 */
    static int pagePadding(Context context) {
        return dp(context, 16);
    }

    /** 药丸、输入框、卡片的通用圆角背景。 */
    static android.graphics.drawable.GradientDrawable rounded(int color, float radiusDp, Context context) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp * context.getResources().getDisplayMetrics().density);
        return d;
    }

    static android.graphics.drawable.GradientDrawable outlined(int stroke, float radiusDp, Context context) {
        android.graphics.drawable.GradientDrawable d = rounded(Color.TRANSPARENT, radiusDp, context);
        d.setStroke(dp(context, 1), stroke);
        return d;
    }
}
