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
    /** 顶栏：表情球常驻正中，两侧是返回/新会话。 */
    private final FrameLayout mHeader;
    private final TextView mHeading;
    private final ImageView mMenu;
    private final MuseBallView mBall;
    private final View mNewChat;
    /** 文件架盖着时用来收起底栏。这一页不占导航上的一格。 */
    private static final Object FILES_NAV = new Object();
    private static final Object ARCHIVE_NAV = new Object();
    private static final Object COMMUNITY_NAV = new Object();
    /** 电脑页盖着时用来收起底栏。这一页不占导航上的一格。 */
    private static final Object COMPUTER_NAV = new Object();
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
    private @Nullable MuseArchiveView mArchive;

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

                    @Override
                    public boolean dispatchTouchEvent(MotionEvent ev) {
                        int action = ev.getActionMasked();
                        if (action != MotionEvent.ACTION_UP
                                && action != MotionEvent.ACTION_CANCEL) {
                            mBall.followScreen(ev.getRawX(), ev.getRawY());
                        }
                        return super.dispatchTouchEvent(ev);
                    }
                };
        mRoot.setBackgroundColor(bg(context));
        mRoot.setClickable(true); // 不让触摸漏到下面的 WebContents

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        // ---- 顶栏：表情球钉在正中，左右各留一格按钮，对得齐。
        mHeader = new FrameLayout(context);
        mHeader.setClipChildren(false);
        mHeader.setPadding(dp(8), dp(4), dp(8), dp(4));
        mMenu = iconButton(context, R.drawable.lemurx_muse_ic_menu, R.string.lemurx_muse_settings);
        showMenuButton();
        FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(dp(44), dp(44));
        menuLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        mHeader.addView(mMenu, menuLp);

        mBall = new MuseBallView(context, 52);
        mBall.setEmotion(MuseBallEmotion.IDLE);
        mBall.setOnLongClickListener(
                v -> {
                    if (mChat != null) mChat.openBallPicker();
                    else {
                        MuseBallPicker.show(context, mBall.emotionId(), mBall::setEmotion);
                    }
                    return true;
                });
        FrameLayout.LayoutParams ballLp = new FrameLayout.LayoutParams(dp(52), dp(52));
        ballLp.gravity = Gravity.CENTER;
        mHeader.addView(mBall, ballLp);

        mHeading = new TextView(context);
        mHeading.setTextColor(text(context));
        mHeading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        mHeading.setTypeface(Typeface.DEFAULT_BOLD);
        mHeading.setText(R.string.lemurx_muse_brand);
        mHeading.setGravity(Gravity.CENTER_VERTICAL);
        mHeading.setVisibility(View.GONE);
        FrameLayout.LayoutParams headingLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headingLp.gravity = Gravity.CENTER_VERTICAL;
        headingLp.setMarginStart(dp(52));
        headingLp.setMarginEnd(dp(52));
        mHeader.addView(mHeading, headingLp);

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
        FrameLayout.LayoutParams newLp = new FrameLayout.LayoutParams(dp(44), dp(44));
        newLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        mHeader.addView(mNewChat, newLp);
        column.addView(mHeader, new LinearLayout.LayoutParams(
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
        mDrawer.setArchiveAction(this::openArchive);
        // 抽屉打开电脑页时带上本标签页的 threadId，页内跑完的命令只挂回这一路对话。
        mDrawer.setComputerAction(() -> showComputer(null, mThreadId));
        updateForUrl(url);
    }

    /**
     * 主页面不在顶栏放三条线。侧边栏统一从底部导航打开，这一格先收起来，二级页再换成返回。
     */
    private void showMenuButton() {
        mMenu.setVisibility(View.GONE);
    }

    /** 顶栏左上角换成返回。档案这类二级页会收起底栏，返回只能留在这里。 */
    private void showBackButton(Runnable onBack) {
        mMenu.setVisibility(View.VISIBLE);
        mMenu.setImageResource(R.drawable.lemurx_console_ic_back);
        mMenu.setContentDescription(mRoot.getContext().getString(R.string.lemurx_archive_back));
        mMenu.setOnClickListener(
                v -> {
                    LemurXHaptics.tick(v);
                    onBack.run();
                });
    }

    /** 盖层顶栏：左上角返回，标题占中间。底栏不在这些页上出现。 */
    private static LinearLayout coverBar(Context context, int title, Runnable onBack) {
        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(context, 4), 0, dp(context, 12), dp(context, 4));
        ImageView back = iconButton(context, R.drawable.lemurx_console_ic_back, R.string.lemurx_archive_back);
        back.setOnClickListener(
                v -> {
                    LemurXHaptics.tick(v);
                    onBack.run();
                });
        bar.addView(back);
        TextView heading = new TextView(context);
        heading.setText(title);
        heading.setTextColor(text(context));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginStart(dp(context, 4));
        bar.addView(heading, titleLp);
        return bar;
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

    /** 这个页面对应的标签。标签卡片要按标签记下对话画面。 */
    public int tabId() {
        return mTab.getId();
    }

    /**
     * 页面上出现验证码：在这个标签的对话里问用户，并停掉正在跑的那一轮，
     * 这样用户可以直接把验证码发回来。
     */
    public static void askCaptcha(int tabId, @Nullable String reason) {
        MuseChatView chat = sChats.get(tabId);
        if (chat != null) chat.askCaptcha(reason);
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

    /** 表情球常驻顶栏正中，打字时也不收。 */
    void setComposeHeaderHidden(boolean unusedHidden) {}

    void setBallEmotion(String emotionId) {
        mBall.setEmotion(emotionId);
    }

    String ballEmotionId() {
        return mBall.emotionId();
    }

    private void showHeaderBall(boolean show) {
        mBall.setVisibility(show ? View.VISIBLE : View.GONE);
        mHeading.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void show(String section) {
        mSection = section;
        mHeader.setVisibility(View.VISIBLE);
        if (mArchive != null && mArchive.getVisibility() == View.VISIBLE) {
            if (mChat != null) mChat.setVisibility(View.GONE);
            if (mCommunity != null) mCommunity.setVisibility(View.GONE);
            mHeading.setText(R.string.lemurx_archive);
            mNewChat.setVisibility(View.INVISIBLE);
            showHeaderBall(false);
            showBackButton(MusePage::backFromArchive);
            return;
        }
        showMenuButton();
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
            showHeaderBall(false);
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
            showHeaderBall(true);
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

    /** 这个会话正挂在哪个对话视图上。关页面停放时可能暂时没有。 */
    static @Nullable MuseChatView chatForThread(String threadId) {
        if (threadId == null || threadId.isEmpty()) return null;
        for (MuseChatView chat : sChats.values()) {
            if (threadId.equals(chat.threadId())) return chat;
        }
        return null;
    }

    /** 有对话正在回复。画中画用它判断按 Home 时要不要收成小窗。 */
    public static boolean hasLiveWork() {
        return MuseChatView.hasLiveWork();
    }

    /**
     * 本机浏览器这一轮还在操作或回复。远程电脑命令不算：用户可以离开屏幕等结果。
     */
    public static boolean hasOnScreenBrowserWork() {
        ThreadUtils.assertOnUiThread();
        for (MuseChatView chat : sChats.values()) {
            if (chat.needsScreen()) return true;
        }
        return false;
    }

    private static int sRemoteHolds;

    /** 远程电脑命令开始或结束时钉住 / 放开后台保活。 */
    static void noteRemoteHold(boolean hold) {
        ThreadUtils.assertOnUiThread();
        if (hold) sRemoteHolds++;
        else if (sRemoteHolds > 0) sRemoteHolds--;
        LemurXComputerHoldImpl.sync(sRemoteHolds > 0);
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

    /** 登录状态变了：对话输入框在 Agent 和「搜索 / 网址」之间切换。 */
    public static void applyAccountMode() {
        ThreadUtils.assertOnUiThread();
        for (MuseChatView chat : sChats.values()) chat.applyAccountMode();
    }

    /** 退出登录时停掉还在回复的对话。 */
    public static void stopAgentWork() {
        ThreadUtils.assertOnUiThread();
        for (MuseChatView chat : new ArrayList<>(sChats.values())) {
            chat.stop();
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

    /** 从左侧滑出设置，不另开一页。底部导航的三条线走到这里。 */
    public void openDrawer() {
        mDrawer.open();
    }

    /** 档案库。人在网页上打开侧边栏时，先回到对话再盖上，避免加在看不见的页面上。 */
    private void openArchive() {
        if (mRoot.getParent() == null) LemurXConsoleShell.goChat();
        showArchive();
    }

    private void showArchive() {
        closeFiles();
        Context context = mRoot.getContext();
        if (mArchive != null) mContent.removeView(mArchive);
        mArchive = new MuseArchiveView(context);
        mContent.addView(mArchive, fill());
        sArchivePage = this;
        if (mChat != null) mChat.setVisibility(View.GONE);
        if (mCommunity != null) mCommunity.setVisibility(View.GONE);
        mHeading.setText(R.string.lemurx_archive);
        mNewChat.setVisibility(View.INVISIBLE);
        showHeaderBall(false);
        showBackButton(MusePage::backFromArchive);
        LemurXConsoleShell.setNavSuppressed(ARCHIVE_NAV, true);
        LemurXConsoleShell.onPlaceChanged();
    }

    private static @Nullable MusePage sFilesPage;
    private static @Nullable MusePage sArchivePage;

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

    /** 底栏要去别处：社区、电脑、文件架、发帖页都先让开，底下原来的页面还在。这不是返回，侧边栏不要跟着再打开。 */
    public static void dismissCovers() {
        boolean had = communityOpen() || computerOpen() || archiveOpen() || filesOpen();
        closeCommunityCompose();
        closeCommunity();
        closeComputer();
        closeFiles();
        closeArchive();
        if (had) MuseDrawer.dropReturn();
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
            int padTop = LemurXConsoleShell.statusInsetPx() + dp(activity, 8);
            sPlaza.followKeyboard(column, 0, padTop, 0, dp(activity, 24));
            LinearLayout bar = coverBar(activity, R.string.lemurx_muse_community, MusePage::backFromCommunity);
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
        LemurXConsoleShell.setNavSuppressed(COMMUNITY_NAV, true);
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

    /**
     * 手势或顶栏返回离开社区。发帖页还开着就只收发帖页。
     * 从侧边栏进来的，整层关掉后回到当时开着的侧边栏。
     */
    public static boolean backFromCommunity() {
        if (closeCommunityCompose()) return true;
        if (!closeCommunity()) return false;
        MuseDrawer.restoreAfterCover();
        return true;
    }

    /** 收起社区这一层。没开着时返回 false。 */
    public static boolean closeCommunity() {
        if (!communityOpen() || sPlaza == null) return false;
        sPlaza.releaseKeyboard();
        sPlaza.setVisibility(View.GONE);
        LemurXConsoleShell.setNavSuppressed(COMMUNITY_NAV, false);
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
        LemurXConsoleShell.setNavSuppressed(FILES_NAV, false);
        LemurXConsoleShell.onPlaceChanged();
        return true;
    }

    /** 档案库正盖着。 */
    public static boolean archiveOpen() {
        MusePage page = sArchivePage;
        return page != null && page.mArchive != null && page.mArchive.getVisibility() == View.VISIBLE;
    }

    /** 手势或顶栏返回离开档案。从侧边栏进来的，回到当时开着的侧边栏。 */
    public static boolean backFromArchive() {
        if (!closeArchive()) return false;
        MuseDrawer.restoreAfterCover();
        return true;
    }

    /** 收起档案库，回到打开它之前的分区。没开着时返回 false。 */
    public static boolean closeArchive() {
        MusePage page = sArchivePage;
        if (page == null || page.mArchive == null) return false;
        page.mContent.removeView(page.mArchive);
        page.mArchive = null;
        sArchivePage = null;
        page.show(page.mSection);
        LemurXConsoleShell.setNavSuppressed(ARCHIVE_NAV, false);
        LemurXConsoleShell.onPlaceChanged();
        return true;
    }

    /** 你的电脑这一层正盖着。它不属于任何标签页。 */
    public static boolean computerOpen() {
        return sComputer != null && sComputer.getVisibility() == View.VISIBLE;
    }

    /** 手势或顶栏返回离开电脑页。从侧边栏进来的，回到当时开着的侧边栏。 */
    public static boolean backFromComputer() {
        if (!closeComputer()) return false;
        MuseDrawer.restoreAfterCover();
        return true;
    }

    /** 收起电脑这一层。底下原来的网页或对话还在。没开着时返回 false。 */
    public static boolean closeComputer() {
        if (!computerOpen() || sComputer == null) return false;
        sComputer.releaseKeyboard();
        sComputer.setVisibility(View.GONE);
        LemurXConsoleShell.setNavSuppressed(COMPUTER_NAV, false);
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

    /** 远程电脑和社区要 Aero 账号。没登录就打开登录，调用方不要继续。 */
    public static boolean requireAccount() {
        if (LemurXAccount.isSignedIn()) return true;
        Activity activity = LemurXConsoleShell.activity();
        if (activity != null) LemurXSignIn.showFromBrowser(activity);
        return false;
    }

    /**
     * 打开电脑页。{@code threadId} 是页内新跑命令要挂回的对话；点卡片打开时应传入该卡片所在线程。
     */
    public static void showComputer(@Nullable String runId, @Nullable String threadId) {
        if (!requireAccount()) return;
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
            // 底栏在这一层不出现，底部只留页面空隙，不再为导航留空。
            sComputer.followKeyboard(column, 0, padTop, 0, dp(activity, 24));
            column.addView(coverBar(activity, R.string.lemurx_muse_computer, MusePage::backFromComputer));
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
        LemurXConsoleShell.setNavSuppressed(COMPUTER_NAV, true);
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

    /** 往前走（换页、打开登录网页）时，不要在回来后再把侧边栏打开。 */
    public static void dropDrawerReturn() {
        MuseDrawer.dropReturn();
    }

    /** 去付款页。侧边栏立刻收掉，返回键不再把它打开。 */
    public static void abandonDrawer() {
        MuseDrawer.abandon();
    }

    /** 附件预览正开着。外壳的返回键先问这个。 */
    public static boolean previewOpen() {
        return MuseChatView.previewOpen();
    }

    /** 收起附件预览。没开着时返回 false。 */
    public static boolean closePreview() {
        return MuseChatView.closePreview();
    }

    /** 推荐或设备、模型、附件选择正开着。返回手势要先收它们，不要去翻网页历史。 */
    public static boolean chatSheetsOpen() {
        return MuseChatView.sheetsOpen();
    }

    /** 收起最上面的一层选择框或推荐。没开着时返回 false。 */
    public static boolean closeChatSheets() {
        return MuseChatView.consumeSheetBack();
    }

    /**
     * 打开应用或回到前台时把这个账号的 Tailscale 节点拉上线，并刷新对话里的在线点。
     * 还没有对话页时也要打这一下，不能等用户再打开远程电脑。
     */
    public static void bringTailnetOnline() {
        ThreadUtils.assertOnUiThread();
        if (!LemurXAccount.isSignedIn()) return;
        if (sChats.isEmpty()) {
            LemurXComputer.ensureNodeOnline(null);
            return;
        }
        for (MuseChatView chat : sChats.values()) chat.bringTargetsOnline();
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
