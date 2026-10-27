// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.lemurx.LemurXConsoleShell;
import org.chromium.chrome.browser.lemurx.LemurXHaptics;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.ui.native_page.BasicSmoothTransitionDelegate;
import org.chromium.chrome.browser.ui.native_page.NativePage;
import org.chromium.chrome.browser.ui.native_page.NativePageHost;
import org.chromium.content_public.browser.LoadUrlParams;

import java.util.ArrayList;
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

    /**
     * 跟 Chrome 的夜间模式走。主题切换会重建 Activity，页面在构造时按当前配置上色即可。
     */
    public static boolean night(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int text(Context context) {
        return night(context) ? 0xFFE7EEF2 : COLOR_TEXT;
    }

    public static int muted(Context context) {
        return night(context) ? 0xFF9AA6B0 : COLOR_MUTED;
    }

    public static int surface(Context context) {
        return night(context) ? 0xFF1C262C : COLOR_SURFACE;
    }

    public static int bg(Context context) {
        return night(context) ? 0xFF10181C : COLOR_BG;
    }

    public static int line(Context context) {
        return night(context) ? 0xFF2C3A42 : COLOR_LINE;
    }

    public static int accent(Context context) {
        return night(context) ? 0xFFE7EEF2 : COLOR_ACCENT;
    }

    /** 压在 {@link #accent} 上的文字和图标。 */
    public static int onAccent(Context context) {
        return night(context) ? COLOR_TEXT : 0xFFFFFFFF;
    }

    public static int link(Context context) {
        return night(context) ? 0xFF8AB4F8 : 0xFF1A73E8;
    }

    public static int error(Context context) {
        return night(context) ? 0xFFFFB4AB : 0xFFB3261E;
    }

    public static int warn(Context context) {
        return night(context) ? 0xFFFFC56A : 0xFFB26A00;
    }

    public static int ok(Context context) {
        return night(context) ? 0xFF7DCEA0 : 0xFF1E7B34;
    }

    public static int outline(Context context) {
        return night(context) ? 0xFF3A4A52 : 0xFFC9CED3;
    }

    public static int track(Context context) {
        return night(context) ? 0xFF2C3A42 : 0xFFD5DADF;
    }

    public static int chipBg(Context context) {
        return night(context) ? 0xFF14332F : 0xFFD9F8F4;
    }

    public static int chipText(Context context) {
        return night(context) ? 0xFF7EEAE0 : 0xFF0C6E66;
    }

    /** 进行中、不可再点的按钮。 */
    public static int disabled(Context context) {
        return night(context) ? 0xFF3A4A52 : 0xFF5C6B76;
    }

    public static int onDisabled(Context context) {
        return night(context) ? 0xFF9AA6B0 : 0xFFFFFFFF;
    }

    /** 登录底栏：比页面再抬一层。亮色保持原来的灰卡片。 */
    public static int sheet(Context context) {
        return night(context) ? 0xFF1C262C : 0xFFEBEBEB;
    }

    public static int handle(Context context) {
        return night(context) ? 0xFF3A4A52 : 0xFFC8C8C8;
    }

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
    private final MuseDrawer mDrawer;

    /**
     * 标签页 id → 这个标签页的对话视图。原生页面在标签页离开 chrome-native:// 时就会被销毁、
     * 回来时重建，但一个标签页就是一个 Agent，它的会话状态（消息、正在流式输出的回复、
     * 没发出去的草稿、滚动位置）要跟着标签页活，而不是跟着页面活：这里把视图缓存起来，
     * 页面重建时直接挂回去，标签页关掉（{@link #onTabDestroyed}）或活动页销毁
     * （{@link #destroyAll}）时才真正销毁。
     */
    private static final Map<Integer, MuseChatView> sChats = new HashMap<>();

    private @Nullable MuseChatView mChat;
    private @Nullable MuseCommunityView mCommunity;
    private @Nullable ScrollView mFiles;

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
        mRoot.setBackgroundColor(bg(context));
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
        mHeading.setTextColor(text(context));
        mHeading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        mHeading.setTypeface(Typeface.DEFAULT_BOLD);
        mHeading.setText(R.string.lemurx_muse_brand);
        header.addView(mHeading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mNewChat = iconButton(context, R.drawable.lemurx_muse_ic_new_chat, R.string.lemurx_muse_new_chat);
        mNewChat.setOnClickListener(
                v -> {
                    LemurXHaptics.tick(v);
                    if (MuseUrls.SECTION_COMMUNITY.equals(mSection) && mCommunity != null) {
                        mCommunity.openCompose();
                        return;
                    }
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
        mDrawer = new MuseDrawer(mRoot);
        mDrawer.setFilesAction(this::openFiles);
        // 抽屉打开电脑页时带上本标签页的 threadId，页内跑完的命令只挂回这一路对话。
        mDrawer.setComputerAction(() -> showComputer(null, mThreadId));
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
        return bg(mRoot.getContext());
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
        if (mCommunity != null) mCommunity.setVisibility(View.GONE);
        if (mFiles != null) mFiles.setVisibility(View.GONE);
        Context context = mRoot.getContext();
        if (MuseUrls.SECTION_COMMUNITY.equals(section)) {
            if (mCommunity == null) {
                mCommunity = new MuseCommunityView(context, mLinks);
                mContent.addView(mCommunity, fill());
            }
            mCommunity.setVisibility(View.VISIBLE);
            mCommunity.refreshNow();
            mHeading.setText(R.string.lemurx_muse_community);
            mNewChat.setContentDescription(context.getString(R.string.lemurx_muse_community_write));
            mNewChat.setVisibility(View.VISIBLE);
        } else {
            if (mChat == null) {
                mChat = obtainChat(context);
                mContent.addView(mChat, fill());
            }
            mChat.setVisibility(View.VISIBLE);
            if (mThreadId != null) mChat.load(mThreadId);
            mHeading.setText(R.string.lemurx_muse_brand);
            mNewChat.setContentDescription(context.getString(R.string.lemurx_muse_new_chat));
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

    /** 有对话正在回复。画中画用它判断按 Home 时要不要收成小窗。 */
    public static boolean hasLiveWork() {
        return MuseChatView.hasLiveWork();
    }

    /** 画中画静画面上的那一句。没有正在进行的回复时是 null。 */
    public static @Nullable String pipStatus() {
        return MuseChatView.anyLiveStatus();
    }

    /** 窗口离开前台：正在操作或回复的对话挂起，等用户点继续。 */
    public static void suspendIfRunning() {
        ThreadUtils.assertOnUiThread();
        for (MuseChatView chat : new ArrayList<>(sChats.values())) {
            chat.suspendForBackground();
        }
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
        if (MuseUrls.SECTION_COMMUNITY.equals(mSection)) return res.getString(R.string.lemurx_muse_community);
        String title = mChat != null ? mChat.title() : null;
        return title != null ? title : res.getString(R.string.lemurx_muse_brand);
    }

    @Override
    public String getHost() {
        return MuseUrls.HOST;
    }

    @Override
    public void destroy() {
        mDrawer.dismiss();
        LemurXConsoleShell.removeNavListener(mNavListener);
        // 对话视图不销毁：从页面上摘下来留给这个标签页下一次的 MusePage，见 sChats。
        if (mChat != null) {
            mContent.removeView(mChat);
            mChat.detachPage(this);
            mChat = null;
        }
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

    /** 顶栏「新会话」：新开一个标签页，里面是一轮新的网页会话。当前这页留着。 */
    private void newChat() {
        LemurXConsoleShell.openNewSession();
    }

    /** 顶栏 ☰：从左侧滑出设置，不另开一页。网页底栏在社区的位置也会走到这里。 */
    public void openDrawer() {
        mDrawer.open();
    }

    private void openSettings() {
        openDrawer();
    }

    /**
     * 文件架。这一页如果还没挂上屏幕（人在网页上打开侧边栏），先回到对话再盖上文件架，
     * 否则列表加在一块看不见的页面上。
     */
    private void openFiles() {
        if (mRoot.getParent() == null) LemurXConsoleShell.goChat();
        showFiles();
    }

    /** 文件架盖在当前分区上。返回键先收它，再收抽屉。 */
    private void showFiles() {
        Context context = mRoot.getContext();
        if (mFiles != null) mContent.removeView(mFiles);
        mFiles = buildFiles(context);
        mContent.addView(mFiles, fill());
        sFilesPage = this;
        if (mChat != null) mChat.setVisibility(View.GONE);
        if (mCommunity != null) mCommunity.setVisibility(View.GONE);
        mHeading.setText(R.string.lemurx_muse_files);
        mNewChat.setVisibility(View.INVISIBLE);
        LemurXConsoleShell.onPlaceChanged();
    }

    private ScrollView buildFiles(Context context) {
        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(8), dp(16), dp(24));
        java.util.List<LemurXFiles.Entry> entries = LemurXFiles.load(context);
        if (entries.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(R.string.lemurx_muse_files_empty);
            empty.setTextColor(muted(context));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            column.addView(empty);
        }
        for (LemurXFiles.Entry entry : entries) {
            column.addView(fileRow(context, entry));
        }
        scroll.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private View fileRow(Context context, LemurXFiles.Entry entry) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackground(rounded(surface(context), 16, context));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        ImageView icon = new ImageView(context);
        int kindIcon = "pdf".equals(entry.kind)
                ? R.drawable.lemurx_muse_ic_file_pdf
                : "doc".equals(entry.kind)
                        ? R.drawable.lemurx_muse_ic_file_doc
                        : "image".equals(entry.kind)
                                ? R.drawable.lemurx_muse_ic_file_image
                                : R.drawable.lemurx_muse_ic_file_text;
        icon.setImageResource(kindIcon);
        icon.setColorFilter(text(context), android.graphics.PorterDuff.Mode.SRC_IN);
        row.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(context);
        name.setText(entry.name);
        name.setTextColor(text(context));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView kind = new TextView(context);
        int kindLabel = "pdf".equals(entry.kind)
                ? R.string.lemurx_muse_file_pdf
                : "doc".equals(entry.kind)
                        ? R.string.lemurx_muse_file_word
                        : "image".equals(entry.kind)
                                ? R.string.lemurx_muse_file_image
                                : R.string.lemurx_muse_file_note;
        kind.setText(kindLabel);
        kind.setTextColor(muted(context));
        kind.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        texts.addView(name);
        texts.addView(kind);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(dp(12));
        row.addView(texts, tlp);
        row.setOnClickListener(v -> openStoredFile(context, entry));
        return row;
    }

    private void openStoredFile(Context context, LemurXFiles.Entry entry) {
        LemurXFiles.open(context, entry.name, entry.kind, entry.text);
    }

    private static @Nullable MusePage sFilesPage;

    /**
     * 盖住整个窗口的一层。底栏那一条不吃触摸，点别的格子能落到导航栏上，由导航栏自己收起这一层再过去。
     */
    private static final class Cover extends FrameLayout {
        private @Nullable LinearLayout mBody;
        private int mPadLeft;
        private int mPadTop;
        private int mPadRight;
        private int mRestBottom;
        private boolean mImeAnimating;

        Cover(Context context) {
            super(context);
        }

        /** 键盘弹出时把这一层抬到键盘上方，并收起底栏。平时留出底栏的高度。 */
        void followKeyboard(LinearLayout body, int left, int top, int right, int restBottom) {
            mBody = body;
            mPadLeft = left;
            mPadTop = top;
            mPadRight = right;
            mRestBottom = restBottom;
            body.setPadding(left, top, right, restBottom);
            setOnApplyWindowInsetsListener((v, insets) -> {
                if (!mImeAnimating) applyIme(null);
                return insets;
            });
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setWindowInsetsAnimationCallback(
                        new WindowInsetsAnimation.Callback(
                                WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                            @Override
                            public void onPrepare(WindowInsetsAnimation animation) {
                                if ((animation.getTypeMask() & WindowInsets.Type.ime()) != 0) {
                                    mImeAnimating = true;
                                }
                            }

                            @Override
                            public WindowInsets onProgress(
                                    WindowInsets insets,
                                    java.util.List<WindowInsetsAnimation> running) {
                                if (mImeAnimating) applyIme(insets);
                                return insets;
                            }

                            @Override
                            public void onEnd(WindowInsetsAnimation animation) {
                                if ((animation.getTypeMask() & WindowInsets.Type.ime()) != 0) {
                                    mImeAnimating = false;
                                    applyIme(null);
                                }
                            }
                        });
            }
        }

        void releaseKeyboard() {
            mImeAnimating = false;
            LemurXConsoleShell.setNavSuppressed(this, false);
            if (mBody != null) mBody.setPadding(mPadLeft, mPadTop, mPadRight, mRestBottom);
        }

        private void applyIme(@Nullable WindowInsets insets) {
            if (mBody == null) return;
            int ime = 0;
            try {
                WindowInsets source = insets != null ? insets : getRootWindowInsets();
                if (source != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ime = source.getInsets(WindowInsets.Type.ime()).bottom;
                }
            } catch (RuntimeException e) {
                ime = 0;
            }
            boolean keyboard = ime > 0 && getVisibility() == View.VISIBLE;
            LemurXConsoleShell.setNavSuppressed(this, keyboard);
            int bottom = keyboard ? ime + dp(getContext(), 12) : mRestBottom;
            if (mBody.getPaddingBottom() != bottom) {
                mBody.setPadding(mPadLeft, mPadTop, mPadRight, bottom);
            }
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (LemurXConsoleShell.touchInNav(this, ev)) return false;
            return super.dispatchTouchEvent(ev);
        }
    }

    /** 发帖页正盖着广场。返回键先收它。 */
    public static boolean closeCommunityCompose() {
        return MuseCommunityView.closeCompose();
    }

    private static @Nullable Cover sPlaza;
    private static @Nullable MuseCommunityView sPlazaView;
    private static @Nullable Cover sComputer;
    private static LemurXComputer.@Nullable Page sComputerPage;
    /**
     * 打开电脑页时记下的对话线程。页内跑完的命令只挂回这一路，不靠 windowToken 猜标签页。
     */
    private static @Nullable String sComputerThreadId;

    /** 底栏要去别处：社区、电脑、文件架、发帖页都先让开，底下原来的页面还在。 */
    public static void dismissCovers() {
        closeCommunityCompose();
        closeCommunity();
        closeComputer();
        closeFiles();
    }

    /** 社区是独立的一层，不写进任何标签页。 */
    public static void showCommunity(Context context) {
        Activity activity = LemurXConsoleShell.activity();
        if (activity == null) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        closeComputer();
        if (sPlaza == null || sPlaza.getParent() != content) {
            if (sPlaza != null && sPlaza.getParent() instanceof ViewGroup) {
                ((ViewGroup) sPlaza.getParent()).removeView(sPlaza);
            }
            sPlaza = new Cover(activity);
            sPlaza.setBackgroundColor(bg(activity));
            sPlaza.setClickable(true);
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            int padLeft = dp(activity, 16);
            int padTop = LemurXConsoleShell.statusInsetPx() + dp(activity, 8);
            int padRight = dp(activity, 12);
            sPlaza.followKeyboard(column, padLeft, padTop, padRight, dp(activity, 72));
            LinearLayout bar = new LinearLayout(activity);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(activity);
            title.setText(R.string.lemurx_muse_community);
            title.setTextColor(text(activity));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView write = new TextView(activity);
            write.setText(R.string.lemurx_muse_community_write);
            write.setTextColor(onAccent(activity));
            write.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            write.setTypeface(Typeface.DEFAULT_BOLD);
            write.setBackground(rounded(accent(activity), 18, activity));
            write.setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8));
            write.setOnClickListener(v -> {
                if (sPlazaView != null) sPlazaView.openCompose();
            });
            bar.addView(write);
            column.addView(bar);
            sPlazaView = new MuseCommunityView(activity, LemurXConsoleShell::openInNewTab);
            column.addView(sPlazaView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            sPlaza.addView(column, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            content.addView(sPlaza, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        sPlaza.setVisibility(View.VISIBLE);
        sPlaza.requestApplyInsets();
        sPlaza.bringToFront();
        LemurXConsoleShell.onPlaceChanged();
        if (sPlazaView != null) sPlazaView.refreshNow();
    }

    /** 盖在窗口上的社区。分享从对话进来时，往这一层的发帖页填草稿。 */
    static @Nullable MuseCommunityView communityCover() {
        return sPlazaView;
    }

    public static boolean communityOpen() {
        return sPlaza != null && sPlaza.getVisibility() == View.VISIBLE;
    }

    /** 盖在网页上的那一层（电脑或社区）。标签卡片要截这一层，而不是底下的网页。 */
    public static @Nullable View foregroundCover() {
        if (computerOpen()) return sComputer;
        if (communityOpen()) return sPlaza;
        return null;
    }

    /** 收起社区这一层。没开着时返回 false。 */
    public static boolean closeCommunity() {
        if (!communityOpen() || sPlaza == null) return false;
        sPlaza.releaseKeyboard();
        sPlaza.setVisibility(View.GONE);
        LemurXConsoleShell.onPlaceChanged();
        return true;
    }

    /** 文件架正盖着。 */
    public static boolean filesOpen() {
        MusePage page = sFilesPage;
        return page != null && page.mFiles != null && page.mFiles.getVisibility() == View.VISIBLE;
    }

    /** 收起文件架，回到打开它之前的分区。没开着时返回 false。 */
    public static boolean closeFiles() {
        MusePage page = sFilesPage;
        if (page == null || page.mFiles == null) return false;
        page.mContent.removeView(page.mFiles);
        page.mFiles = null;
        sFilesPage = null;
        page.show(page.mSection);
        LemurXConsoleShell.onPlaceChanged();
        return true;
    }

    /** 你的电脑这一层正盖着。它不属于任何标签页。 */
    public static boolean computerOpen() {
        return sComputer != null && sComputer.getVisibility() == View.VISIBLE;
    }

    /** 收起电脑这一层。底下原来的网页或对话还在。没开着时返回 false。 */
    public static boolean closeComputer() {
        if (!computerOpen() || sComputer == null) return false;
        sComputer.releaseKeyboard();
        sComputer.setVisibility(View.GONE);
        LemurXConsoleShell.onPlaceChanged();
        return true;
    }

    /** 你的电脑。和社区一样盖在所有标签页上，不改当前标签的地址。 */
    public static void showComputer() {
        showComputer(null, null);
    }

    /** 打开电脑页；{@code runId} 非空时聚焦对话或页内登记的那一次运行。 */
    public static void showComputer(@Nullable String runId) {
        showComputer(runId, null);
    }

    /**
     * 打开电脑页。{@code threadId} 是页内新跑命令要挂回的对话；点卡片打开时应传入该卡片所在线程。
     */
    public static void showComputer(@Nullable String runId, @Nullable String threadId) {
        Activity activity = LemurXConsoleShell.activity();
        if (activity == null) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        if (threadId != null && !threadId.isEmpty()) {
            sComputerThreadId = threadId;
        }
        closeCommunityCompose();
        closeCommunity();
        if (sComputer == null || sComputer.getParent() != content) {
            if (sComputer != null && sComputer.getParent() instanceof ViewGroup) {
                ((ViewGroup) sComputer.getParent()).removeView(sComputer);
            }
            sComputer = new Cover(activity);
            sComputer.setBackgroundColor(bg(activity));
            sComputer.setClickable(true);
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            int padTop = LemurXConsoleShell.statusInsetPx() + dp(activity, 8);
            sComputer.followKeyboard(column, 0, padTop, 0, dp(activity, 72));
            TextView title = new TextView(activity);
            title.setText(R.string.lemurx_muse_computer);
            title.setTextColor(text(activity));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setPadding(dp(activity, 16), 0, dp(activity, 12), dp(activity, 8));
            column.addView(title);
            sComputerPage = new LemurXComputer.Page(activity);
            column.addView(sComputerPage, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            sComputer.addView(column, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            content.addView(sComputer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        sComputer.setVisibility(View.VISIBLE);
        sComputer.requestApplyInsets();
        sComputer.bringToFront();
        if (runId != null && sComputerPage != null) sComputerPage.focusRun(runId);
        LemurXConsoleShell.onPlaceChanged();
    }

    /** 电脑页开跑时用的 originating thread；没有就返回 null，不强行猜。 */
    static @Nullable String computerThreadId() {
        return sComputerThreadId;
    }

    /**
     * 页内跑完的命令：只挂到 {@code threadId} 对应的对话。多标签页时不会串到别的会话。
     */
    static void offerComputerRun(String runId, String command, @Nullable String threadId) {
        ThreadUtils.assertOnUiThread();
        String target = threadId != null && !threadId.isEmpty() ? threadId : sComputerThreadId;
        if (target == null || target.isEmpty()) return;
        for (MuseChatView chat : sChats.values()) {
            if (target.equals(chat.threadId())) {
                chat.offerComputerRun(runId, command);
                return;
            }
        }
    }

    /** 抽屉正开着。外壳的返回键先问这个。 */
    public static boolean drawerOpen() {
        return MuseDrawer.isOpen();
    }

    /** 收起抽屉。没开着时返回 false。 */
    public static boolean closeDrawer() {
        return MuseDrawer.closeIfOpen();
    }

    /** 附件预览正开着。外壳的返回键先问这个。 */
    public static boolean previewOpen() {
        return MuseChatView.previewOpen();
    }

    /** 收起附件预览。没开着时返回 false。 */
    public static boolean closePreview() {
        return MuseChatView.closePreview();
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
