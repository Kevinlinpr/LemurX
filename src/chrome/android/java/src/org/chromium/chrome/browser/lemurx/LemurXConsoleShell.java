// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.util.Base64;
import android.util.Rational;
import android.util.TypedValue;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowInsetsController;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import org.chromium.base.Callback;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.cc.mojom.RootScrollOffsetUpdateFrequency;
import org.chromium.build.annotations.Nullable;
import org.chromium.cc.input.BrowserControlsState;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.browser_controls.BrowserControlsStateProvider;
import org.chromium.chrome.browser.compositor.CompositorViewHolder;
import org.chromium.chrome.browser.browser_controls.BrowserControlsStateProvider.Observer;
import org.chromium.chrome.browser.fullscreen.BrowserControlsManager;
import org.chromium.chrome.browser.layouts.LayoutStateProvider;
import org.chromium.chrome.browser.layouts.LayoutStateProvider.LayoutStateObserver;
import org.chromium.chrome.browser.layouts.LayoutType;
import org.chromium.chrome.browser.back_press.BackPressManager;
import org.chromium.chrome.browser.lemurx.agent.LemurXDeviceBridge;
import org.chromium.chrome.browser.lemurx.muse.LemurXExtensions;
import org.chromium.chrome.browser.lemurx.muse.LemurXWelcome;
import org.chromium.chrome.browser.lemurx.muse.MusePage;
import org.chromium.chrome.browser.lemurx.muse.MuseUrls;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.profiles.ProfileManager;
import org.chromium.chrome.browser.lifecycle.DestroyObserver;
import org.chromium.chrome.browser.lifecycle.StartStopWithNativeObserver;
import org.chromium.chrome.browser.omnibox.OmniboxStub;
import org.chromium.chrome.browser.omnibox.UrlFocusChangeListener;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabBrowserControlsConstraintsHelper;
import org.chromium.chrome.browser.tab.TabCreationState;
import org.chromium.chrome.browser.tab.TabLaunchType;
import org.chromium.chrome.browser.tab.TabSelectionType;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tabmodel.TabModelObserver;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.tabmodel.TabModelSelectorTabObserver;
import org.chromium.chrome.browser.tabmodel.TabModelUtils;
import org.chromium.chrome.browser.toolbar.ToolbarManager;
import org.chromium.components.omnibox.AutocompleteInput;
import org.chromium.components.omnibox.OmniboxFocusReason;
import org.chromium.content_public.browser.GestureListenerManager;
import org.chromium.content_public.browser.GestureStateListener;
import org.chromium.chrome.browser.search_engines.TemplateUrlServiceFactory;
import org.chromium.components.embedder_support.util.UrlUtilities;
import org.chromium.components.search_engines.TemplateUrlService;
import org.chromium.components.url_formatter.UrlFormatter;
import org.chromium.content_public.browser.LoadUrlParams;
import org.chromium.content_public.browser.NavigationController;
import org.chromium.content_public.browser.NavigationEntry;
import org.chromium.content_public.browser.NavigationHandle;
import org.chromium.content_public.browser.NavigationHistory;
import org.chromium.content_public.browser.RenderWidgetHostView;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.base.WindowAndroid;
import org.chromium.ui.util.TokenHolder;
import org.chromium.url.GURL;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LemurX 控制台外壳（纯原生）。
 *
 * <p>控制台页面（对话 / 社区）现在是 Chromium 的 NativePage（{@code chrome-native://lemurx/…}，
 * 见 {@link org.chromium.chrome.browser.lemurx.muse.MusePage}），每个标签页就是一轮对话，并且
 * 记着自己正在看的网页；网页版 OpenMuse 不再参与。个人数据是全局的，放在 ☰ 设置里，不在导航栏。
 * 这个类负责：
 *
 * <ul>
 *   <li>所有网页都不留 Chrome 自己的地址栏、进度条和底部控件：{@link
 *       TabBrowserControlsConstraintsHelper#getConstraints} 先问 {@link #controlsConstraint()}，
 *       平时是 HIDDEN，只有用户主动唤起地址栏那一小会儿是 SHOWN。这是硬约束，加载中、出错页、
 *       换渲染进程这些会把工具栏拉出来的时机都不例外，所以不会再"莫名其妙地出现"。
 *   <li>新标签页就是 Agent 界面（{@link LemurXChromeHost#newTabUrlOverride} →
 *       {@link #launchUrl()} = {@code chrome-native://lemurx/chat/<新 id>}）。没登录时输入框只打开网址或搜索。
 *   <li>在每一页底部浮一条 LemurX 导航栏（白色药丸、lucide 图标、选中格 #F0F1F2）。
 *       网页上从左侧长出前进和后退，网页钮收起。不是网页时显示网页钮和对话钮。其余是搜索、标签页、菜单。
 *       标签页管理（Hub）、地址栏打开期间、
 *       原生页面里软键盘弹出期间收起来。
 *   <li>浏览网页时底栏盖在网页上面。手指往下滚过一小段就收起，往上滚一点或回到页顶就出现；
 *       程序自己滚的不算。对话、社区这些原生页面上不跟着滚收起。
 *   <li>每个标签页就是一个智能体，所以从网页切到对话 / 社区时先把当前网页截一张图记在标签页上
 *       （{@link #tabScreenshot}），对话发消息时随消息一起交给模型，让它看得到"这个标签页正在看
 *       什么"。
 *   <li>按当前地址点亮对应的格（{@link #activeIndex}）：普通网页点亮"网页"，原生页面按分区。
 *       对话 / 社区在网页上点一下就在同一个标签页里切到原生页面（压一条历史，返回键回网页）；
 *       网页格在原生页面上回到记下的网页（上一条历史就是它的话直接后退，否则重新加载）。
 *   <li>旧网页版的搜索 / 标签页按钮会导航到 <code>/__lemurx/omnibox</code> 和
 *       <code>/__lemurx/tabs</code>，在 {@link
 *       org.chromium.chrome.browser.tab.TabObserver#onDidStartNavigationInPrimaryMainFrame}
 *       里截下来，当成原生动作执行，不真的去加载。
 * </ul>
 *
 * <p>旧网页版的首页地址仍存在独立的 SharedPreferences 里（通过 {@link LemurXChromeHost#setNewTabUrl}
 * 同步过来），只用于识别"这是不是旧网页首页"，不再作为新标签页。
 *
 * <p>全部在 UI 线程上。所有回调都跑在 Chromium 的观察者遍历里，任何异常都不能抛回去。
 */
public final class LemurXConsoleShell {
    private static final String TAG = "LemurXConsole";

    private static final String PREF_FILE = "lemurx_console";
    private static final String KEY_ENABLED = "enabled";
    /** 标签页 id → 会话 id，进程被杀后还要能回到同一轮对话。 */
    private static final String KEY_TAB_THREADS = "tab_threads";
    /** 上次停住的页面。重新打开时直接回到这里，不另起一个空对话。 */
    private static final String KEY_LAST_URL = "last_url";
    private static final String KEY_LAST_SECTION = "last_section";
    private static final String KEY_LAST_THREAD = "last_thread";
    private static final String KEY_HOME = "home_url";

    /** OpenMuse 网页版（Expo web 构建）的默认地址。 */
    static final String DEFAULT_HOME = "http://192.168.0.37:8081";

    /** 网页端 requestBrowserSurface() 导航到的路径（apps/mobile App.tsx）。 */
    private static final String SURFACE_OMNIBOX = "/__lemurx/omnibox";

    private static final String SURFACE_TABS = "/__lemurx/tabs";

    /**
     * 首页 hash 标记：<code>#lx/&lt;section&gt;[/&lt;thread&gt;]</code>。网页端（apps/mobile
     * src/shell.ts）用同一前缀判断自己是否跑在外壳里，并从中读 / 写当前分区；chat 分区后面可以再跟
     * 一段会话 id，由网页维护，这里只负责原样保留、不解释。
     */
    private static final String SECTION_PREFIX = "lx/";

    /** 药丸前三格的下标：网页 / 社区 / 对话。 */
    private static final int CELL_WEB = 0;

    private static final int CELL_COMMUNITY = 1;
    private static final int CELL_CHAT = 2;
    /** 不是导航格，只用来标明盖在上面的「你的电脑」或文件架。 */
    private static final int PLACE_COMPUTER = 3;
    private static final int PLACE_FILES = 4;

    /** 地址栏需要先滑出来再聚焦；这是工具栏入场动画大致用的时间。 */
    private static final long OMNIBOX_REVEAL_MS = 150;

    /** 发现工具栏在 HIDDEN 期间露出来后，等多久再重新压回去（给正在进行的收起动画留时间）。 */
    private static final long ENFORCE_DELAY_MS = 120;

    /** 截图最长边不超过这个像素数（够模型看清，又不至于每条消息带几 MB）。 */
    private static final int SHOT_MAX_SIDE = 1280;

    /** 截图落盘的临时目录名（在应用 cache 下），读完即删。 */
    private static final String SHOT_DIR = "lemurx_shots";

    private static @Nullable ChromeTabbedActivity sActivity;
    private static @Nullable LinearLayout sNav;
    /** 画中画里收起导航、对话和进度条，避免整页按钮缩成一堆。 */
    private static boolean sPipChrome;
    private static @Nullable FrameLayout sPipCard;
    /**
     * 进小窗前网页的像素尺寸。小窗里合成器仍按这个尺寸排版，再用缩放把整页放进窗口，
     * 这样 Agent 的点击和滚动还在原来的页面上，用户看到的是整页缩小而不是重排后的窄栏。
     */
    private static int sPipPageWidth;
    private static int sPipPageHeight;
    /**
     * 进小窗前 Blink 视口。比合成器略小（工具栏占掉的部分）。键盘弹起时视口会被缩短，
     * 这里记的是补回键盘高度之后的整页，避免小窗下半截是空的。
     */
    private static int sPipWebWidth;
    private static int sPipWebHeight;
    private static int sPipImeSeen = -1;
    private static @Nullable View sPipImeDecor;
    private static ViewTreeObserver.@Nullable OnGlobalLayoutListener sPipImeLayout;
    private static boolean sPipFitPosted;
    private static View.@Nullable OnLayoutChangeListener sPipScaleListener;
    private static @Nullable View sPipScaleParent;
    private static final View[] sPipClipViews = new View[6];
    private static final boolean[] sPipClipChildren = new boolean[6];
    private static final boolean[] sPipClipToPadding = new boolean[6];
    private static int sPipClipCount;
    private static float sPipLoggedScale;

    /**
     * 网页顶部的细进度条。Chrome 自己的进度条在工具栏里，而工具栏被藏掉了，于是进程被系统回收
     * 后恢复会话、或者慢网络打开网页时，用户看到的只是一整屏白；这条线让"正在加载"看得见。
     */
    private static @Nullable View sProgress;

    /** 进度条当前对应的标签页 id；-1 表示没在显示。 */
    private static int sProgressTabId = -1;

    private static final int PROGRESS_HEIGHT_DP = 2;
    private static final long PROGRESS_FADE_MS = 220;

    /** 等服务器响应期间没有进度事件，让线自己慢慢爬到这里，表示"还在等"。 */
    private static final float PROGRESS_CREEP_TO = 0.85f;

    private static final long PROGRESS_CREEP_MS = 15000;

    /** 导航栏不含系统手势条的基础内边距（左右、上、下）；显示时再加上手势条高度。 */
    private static int sNavPadH;

    private static int sNavPadTop;
    private static int sNavPadBottom;

    /** 网页上导航栏是否因为往下滚而收起。进入一个新网页（换标签页 / 换页）时重置为显示。 */
    private static boolean sWebCollapsed;

    /** 网页累计往下滚过这么多 dp 就收起导航栏（挡掉手指刚按下时的抖动）。 */
    private static final int NAV_HIDE_DP = 8;

    /** 累计往上滚过这么多 dp 就出现（再慢的回看也要出现）。 */
    private static final int NAV_SHOW_DP = 2;

    /** 滚动偏移在这以内算页顶，导航栏一定出现。 */
    private static final int NAV_TOP_DP = 4;

    /** 上一次 refresh 时处于"网页模式"的标签页 id；-1 表示上次不是网页模式。用来发现"刚进入网页"。 */
    private static int sWebModeTabId = -1;

    /** 正在监听滚动手势的网页内容（按滚动偏移收起 / 展开导航栏）。 */
    private static @Nullable WebContents sGestureContents;
    private static @Nullable GestureStateListener sGestureListener;

    /** 正在截图：期间不要再发起第二次。 */
    private static boolean sCapturing;

    /** 标签页上记着的网页截图。 */
    private static final class Shot {
        final String url;
        final String jpegBase64;

        Shot(String url, String jpegBase64) {
            this.url = url;
            this.jpegBase64 = jpegBase64;
        }
    }

    /** 标签页 id → 最近一次网页标题，分享到社区时用。 */
    private static final Map<Integer, String> sTabWebTitles = new HashMap<>();

    /** 这个标签页正在看的网页，给社区分享用。没有网页时返回 null。 */
    public static final class SharedPage {
        public final String url;
        public final String title;

        SharedPage(String url, String title) {
            this.url = url;
            this.title = title;
        }
    }

    public static @Nullable SharedPage currentPage() {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return null;
        try {
            if (!activity.areTabModelsInitialized()) return null;
            Tab tab = activity.getActivityTab();
            if (tab == null) return null;
            String url = null;
            String title = "";
            GURL current = tab.getUrl();
            if (current != null && current.isValid()) {
                String scheme = current.getScheme();
                if (("http".equals(scheme) || "https".equals(scheme)) && !isHome(current.getSpec())) {
                    url = current.getSpec();
                    String live = tab.getTitle();
                    if (!TextUtils.isEmpty(live)) title = live;
                }
            }
            if (url == null) url = tabWebUrl(tab);
            if (url == null) return null;
            if (TextUtils.isEmpty(title)) {
                String saved = sTabWebTitles.get(tab.getId());
                if (saved != null) title = saved;
            } else {
                sTabWebTitles.put(tab.getId(), title);
            }
            return new SharedPage(url, title);
        } catch (Exception e) {
            logi("currentPage", e.getMessage());
            return null;
        }
    }

    /** 标签页 id → 从网页切去对话 / 社区时截的图。 */
    private static final Map<Integer, Shot> sTabShots = new HashMap<>();

    /**
     * 网页上的 Agent 覆盖层。一个标签页绑一个 Agent：在网页上点"对话 / 社区"时，不再把标签页
     * 导航到 chrome-native:// 原生页（那会让网页进后退缓存甚至被丢掉，切回来就是一次刷新，
     * Google 这类 no-store 页面每次都重载），而是把 MusePage 画在活着的网页上面。网页不导航、
     * 不刷新、不堆历史；切回网页只是把覆盖层拿掉。地址栏聚焦时覆盖层让位（标签页本来就在网页上，
     * 搜索天然基于网页），退出地址栏立刻回来，没有网页闪现。
     */
    private static @Nullable FrameLayout sOverlayHost;

    /** 标签页 id → 它的覆盖层页面（按需创建，标签页关掉时销毁）。 */
    private static final Map<Integer, MusePage> sOverlays = new HashMap<>();

    /** 标签页 id → 覆盖层当前显示的分区；没有条目 = 这个标签页看的是网页本身。 */
    private static final Map<Integer, String> sOverlaySection = new HashMap<>();

    /** 离开对话 / 社区 / 电脑时的画面。标签卡片用它，不用底下那张网页。 */
    private static final Map<Integer, Bitmap> sCards = new HashMap<>();

    /** 人停在 Agent 对话上时的画面。标签管理用它，不用底下那张网页。 */
    private static final Map<Integer, Bitmap> sChatCards = new HashMap<>();

    /** 打开标签管理前刚截下的那一页。下一帧的缩略图采集直接用它。 */
    private static int sFrozenCardTab = Tab.INVALID_TAB_ID;

    /** 现在挂在 sOverlayHost 里的页面。 */
    private static @Nullable MusePage sOverlayAttached;

    /** 软键盘正在做进出动画：这期间底部内边距跟着每一帧走，不吃 onApplyWindowInsets 的终值。 */
    private static boolean sOverlayImeAnimating;
    /** 键盘动画时把网页合成层藏起来，避免它从对话底下闪出来。 */
    private static boolean sCompositorHiddenForIme;

    /** 网页 / 社区 / 对话，下标是 CELL_*；用来点亮选中的那格。视觉顺序里社区在最右。 */
    private static @Nullable View[] sSectionCells;
    /** 标签按钮。浏览网页时收起，给前进后退让位。 */
    private static @Nullable View sTabsCell;
    /** 网页模式下占住社区那一格，打开侧边栏。对话模式权重为 0。 */
    private static @Nullable View sMenuCell;
    /** 装了插件才出现，夹在对话和搜索中间。点一下才展开上面的插件栏。 */
    private static @Nullable View sExtensionsCell;
    private static boolean sHasExtensions;
    private static boolean sExtensionsBarOpen;
    /** 网页模式才展开的前进 / 后退格；不是网页时 weight 收到 0。 */
    private static @Nullable View sBackCell;
    private static @Nullable View sForwardCell;
    /** 当前这一页：CELL_WEB / CELL_CHAT / CELL_COMMUNITY / PLACE_COMPUTER / PLACE_FILES。 */
    private static int sPlace = CELL_WEB;
    /** 标签页按钮右下角的数量。当前这个窗口（普通或无痕）里还开着的标签数。 */
    private static @Nullable TextView sTabCount;
    private static @Nullable TabModelObserver sTabCountObserver;
    /** 底栏正在展示网页前进后退。网页钮收起，对话钮占到它的位置。 */
    private static boolean sWebChrome;
    /**
     * 对话钮跟着网页态一起长出来或收起。已经单独露在底栏上时保持显示，只和网页钮交换位置。
     */
    private static boolean sChatTracksChrome;
    /** 这个标签页有没有可回的网页。没有时网页钮压暗。 */
    private static boolean sWebReady = true;
    private static boolean sHistBack;
    private static boolean sHistForward;
    private static @Nullable ValueAnimator sChromeAnim;
    private static final long CHROME_MORPH_MS = 280;
    private static final PathInterpolator CHROME_EASE =
            new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private static @Nullable TabModelSelectorTabObserver sTabObserver;
    private static @Nullable Callback<@Nullable Tab> sActivityTabObserver;
    private static @Nullable LayoutStateProvider sLayoutProvider;
    private static @Nullable LayoutStateObserver sLayoutObserver;
    private static @Nullable OmniboxStub sOmniboxStub;
    private static @Nullable UrlFocusChangeListener sOmniboxListener;
    private static @Nullable DestroyObserver sDestroyObserver;
    private static @Nullable StartStopWithNativeObserver sStartObserver;

    /** 用户正通过搜索按钮使用地址栏：这期间工具栏露出来，导航栏收起。 */
    private static boolean sOmniboxOpen;
    /**
     * 地址栏还在滑出来。这段时间底栏留在原地并淡出，对话不要跟着往下掉。
     * 聚焦之后再真正收起底栏。
     */
    private static boolean sSearchHandoff;
    /** 底栏上次占的高度。搜索打开后页面继续留着这块，避免对话框下沉。 */
    private static int sNavHeight;
    private static boolean sSearchBlank;
    private static float sSearchX;
    private static float sSearchY;

    /**
     * 从原生页面（对话 / 社区）点搜索时先回到这个标签页的网页再聚焦地址栏，让搜索基于网页
     * （地址栏里有"当前网页"卡片、建议也跟着网页走）。这里记着出发的原生页面地址和标签页 id；
     * 用户没搜就退出时回去，搜了就留在结果页。
     */
    private static @Nullable String sSearchReturnUrl;

    /** 点搜索时回到的那张网页；退出地址栏时标签页还是它，就说明没搜。 */
    private static @Nullable String sSearchWebUrl;

    private static int sSearchReturnTabId = -1;

    /** 退出地址栏后等这么久再判断"有没有搜"：提交搜索的导航要一点时间才改掉标签页地址。 */
    private static final long SEARCH_RETURN_DELAY_MS = 350;

    /** 当前显示的布局；不是 BROWSING（例如 Hub）时导航栏收起。 */
    private static @LayoutType int sLayout = LayoutType.BROWSING;

    /** 约束为 HIDDEN 期间持有的 BrowserControlsManager hiding token。 */
    private static int sHidingToken = TokenHolder.INVALID_TOKEN;

    /** 工具栏 offset 监听器；见 {@link #observeControls}。 */
    private static @Nullable Observer sControlsObserver;

    /** 已经排了一次延迟的"重新压回去"，避免重复排队。 */
    private static boolean sEnforcePending;

    /** 原生页面（对话输入框弹出软键盘时）要求暂时收起导航栏的请求方；非空就收起。 */
    private static final Set<Object> sNavSuppressors = new HashSet<>();

    /** 导航栏显隐 / 高度变化的监听者（原生页面据此调整底部内边距）。 */
    private static final List<Runnable> sNavListeners = new ArrayList<>();

    /** 标签页 id → 它的会话 id。原生页面对象重建（例如切去网页再回来）后也能回到同一轮对话。 */
    private static final Map<Integer, String> sTabThreads = new HashMap<>();

    /** {@link #sTabThreads} 是否已经从 {@link #KEY_TAB_THREADS} 灌过；没灌过的空表不能当“没有会话”。 */
    private static boolean sTabThreadsLoaded;

    /** 标签页 id → 它最近看过的普通网页（http/https）。"网页"格回这里；开场建议据此给"总结当前网页"。 */
    private static final Map<Integer, String> sTabWebUrls = new HashMap<>();

    private LemurXConsoleShell() {}

    // ---------------------------------------------------------------- 开关与首页地址

    private static SharedPreferences prefs() {
        return ContextUtils.getApplicationContext()
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    /** 控制台外壳是否启用。默认开；只留给 adb / 调试关掉，界面上没有入口。 */
    public static boolean isEnabled() {
        try {
            return prefs().getBoolean(KEY_ENABLED, true);
        } catch (Exception e) {
            return true;
        }
    }

    /** 首页（OpenMuse 网页）的地址，无尾部斜杠。 */
    public static String homeUrl() {
        String url = null;
        try {
            url = prefs().getString(KEY_HOME, null);
        } catch (Exception e) {
            // 读不到就用默认
        }
        if (TextUtils.isEmpty(url)) url = DEFAULT_HOME;
        return stripTrailingSlashes(url);
    }

    /**
     * 改首页地址（{@link LemurXChromeHost#setNewTabUrl} 转过来）。空值忽略：外壳必须始终有一个
     * 首页，否则"回首页"无处可去。
     */
    public static void setHomeUrl(@Nullable String url) {
        if (TextUtils.isEmpty(url)) return;
        // 只存裸地址：传进来的可能已经带了 #lx/… 标记（launchUrl）或别的 hash / query。
        String clean = stripTrailingSlashes(bareUrl(url));
        try {
            prefs().edit().putString(KEY_HOME, clean).apply();
        } catch (Exception e) {
            logi("setHomeUrl", e.getMessage());
        }
        ThreadUtils.runOnUiThread(LemurXConsoleShell::refresh);
    }

    /**
     * 新标签页实际加载的地址：一轮 Agent 界面（{@code chrome-native://lemurx/chat/<新 id>}）。
     * 每个新标签页都是新会话。没登录时这个界面的输入框只打开网址或搜索。
     */
    public static String launchUrl() {
        return MuseUrls.newChatUrl();
    }

    /**
     * 新标签页就是 Agent 界面：任何要加载原生 NTP 的地方（新标签、主页键、手输 chrome://newtab、
     * 恢复的会话条目）都改成这个标签页自己的对话。标签页已经有会话就接着那轮，没有就新开一轮并记到
     * 标签页上。无痕窗口或外壳关着时不改，返回 null。
     *
     * @param tab 正在加载 NTP 的标签页；用来复用它已经记着的会话。没有（不该发生）就新开一轮。
     * @return 要改成的对话地址；不用改返回 null。
     */
    public static @Nullable String rewriteNewTabUrl(
            @Nullable String url, boolean incognito, @Nullable Tab tab) {
        if (incognito || !isEnabled() || TextUtils.isEmpty(url)) return null;
        try {
            if (!UrlUtilities.isNtpUrl(url)) return null;
        } catch (Exception e) {
            return null;
        }
        if (tab != null) {
            String existing = threadOfTab(tab);
            if (existing != null) return MuseUrls.chatUrl(existing);
        }
        String chat = MuseUrls.newChatUrl();
        if (tab != null) {
            String thread = MuseUrls.threadOf(chat);
            if (thread != null) setTabThread(tab, thread);
        }
        return chat;
    }

    /**
     * 恢复的会话里有 NTP 条目：原生页面工厂已经直接给了对话页，这里把历史条目换成对话地址，
     * 让标签页的地址和它显示的内容一致（导航栏点亮"对话"、搜索知道没有网页上下文）。
     */
    public static void replaceNewTabEntry(Tab tab, String chatUrl) {
        PostTask.postTask(
                TaskTraits.UI_DEFAULT,
                () -> {
                    try {
                        if (tab.isDestroyed()) return;
                        if (!UrlUtilities.isNtpUrl(tab.getUrl().getSpec())) return;
                        LoadUrlParams params = new LoadUrlParams(chatUrl);
                        params.setShouldReplaceCurrentEntry(true);
                        tab.loadUrl(params);
                    } catch (Exception e) {
                        logi("replaceNewTabEntry", e.getMessage());
                    }
                });
    }

    /**
     * 从地址的 hash 里读外壳标记携带的分区名（不含会话段）；没有标记返回 null，有标记但分区为空
     * 返回空串。
     */
    static @Nullable String sectionOf(@Nullable String url) {
        if (TextUtils.isEmpty(url)) return null;
        int hash = url.indexOf('#');
        if (hash < 0) return null;
        String fragment = url.substring(hash + 1);
        if (!fragment.startsWith(SECTION_PREFIX)) return null;
        String section = fragment.substring(SECTION_PREFIX.length());
        int cut = section.indexOf('?');
        if (cut >= 0) section = section.substring(0, cut);
        cut = section.indexOf('/');
        if (cut >= 0) section = section.substring(0, cut);
        return section;
    }

    /** 带外壳标记的首页：网页自己的导航栏不显示，由原生栏接管。 */
    static boolean isShellHome(@Nullable String url) {
        return isHome(url) && sectionOf(url) != null;
    }

    /**
     * 当前地址对应导航栏哪一格该点亮：原生页面按 MuseUrls 的分区（社区 / 对话），其余（普通网页、
     * 旧网页首页）点亮"网页"。
     */
    static int activeIndex(@Nullable String url) {
        String muse = MuseUrls.sectionOf(url);
        if (muse == null) return CELL_WEB;
        return MuseUrls.SECTION_COMMUNITY.equals(muse) ? CELL_COMMUNITY : CELL_CHAT;
    }

    /** 这个地址是不是首页本身（忽略 query / hash / 尾部斜杠）。 */
    public static boolean isHome(@Nullable String url) {
        if (TextUtils.isEmpty(url)) return false;
        return stripTrailingSlashes(bareUrl(url)).equals(homeUrl());
    }

    /** 去掉 hash 和 query。 */
    private static String bareUrl(String url) {
        String bare = url;
        int hash = bare.indexOf('#');
        if (hash >= 0) bare = bare.substring(0, hash);
        int query = bare.indexOf('?');
        if (query >= 0) bare = bare.substring(0, query);
        return bare;
    }

    private static String stripTrailingSlashes(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') end--;
        return s.substring(0, end);
    }

    // ---------------------------------------------------------------- 浏览器控件约束

    /**
     * 供 {@link TabBrowserControlsConstraintsHelper} 读取。外壳启用时所有标签都是 HIDDEN；
     * 用户正用地址栏时是 SHOWN；外壳关掉时返回 BOTH，把决定权交还给 Chromium。
     */
    public static @BrowserControlsState int controlsConstraint() {
        if (!isEnabled()) return BrowserControlsState.BOTH;
        return sOmniboxOpen ? BrowserControlsState.SHOWN : BrowserControlsState.HIDDEN;
    }

    /**
     * 把当前约束推给前台标签（渲染进程那边的 cc 约束），同时把 Java 侧的工具栏视图也摆到位。
     *
     * <p>两边都要做：地址栏聚焦时 ToolbarManager 会拿一个 persistent-show token，
     * BrowserControlsManager 直接把 Android 视图挪到"显示"位置；token 释放后没有人再把它们
     * 挪回去，要等渲染进程下一帧带着新 offset 过来，静止页面上可能是好几秒。所以 HIDDEN 时
     * 这里自己跑一遍 Java 侧的收起动画，并且持有一个 hiding token，让视图在约束是 HIDDEN 的
     * 整段时间里都不可见。
     */
    private static void applyControls(boolean animate) {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return;
        int constraint = controlsConstraint();
        try {
            Tab tab = activity.getActivityTab();
            if (tab != null && tab.getWebContents() != null) {
                TabBrowserControlsConstraintsHelper.update(tab, constraint, animate);
            }
        } catch (Exception e) {
            logi("applyControls", e.getMessage());
        }
        try {
            BrowserControlsManager manager = activity.getBrowserControlsManager();
            if (manager == null) return;
            if (constraint == BrowserControlsState.HIDDEN) {
                if (sHidingToken == TokenHolder.INVALID_TOKEN) {
                    sHidingToken = manager.hideAndroidControlsAndClearOldToken(sHidingToken);
                }
                manager.hideAndroidControls(animate);
            } else if (sHidingToken != TokenHolder.INVALID_TOKEN) {
                manager.releaseAndroidControlsHidingToken(sHidingToken);
                sHidingToken = TokenHolder.INVALID_TOKEN;
            }
        } catch (Exception e) {
            logi("applyControls views", e.getMessage());
        }
    }

    /**
     * 监听工具栏 offset / 高度变化，发现约束是 HIDDEN 时工具栏却露出来了，就重新把它压回去。
     *
     * <p>渲染进程里的 cc 有几条不看 permitted_state 的路径会把控件重新拉出来，最典型的是软键盘
     * 收起时底部控件高度从 0 变回来触发的"高度变化动画"（地址栏取消后工具栏会挂 3 秒就是它）。
     * 这里不去猜具体是哪条路径，统一做法：只要 HIDDEN 期间露出来，去抖 {@link #ENFORCE_DELAY_MS}
     * 后再推一次 HIDDEN。推 HIDDEN 对已经藏好的控件是空操作，所以不会循环。
     */
    private static void observeControls(ChromeTabbedActivity activity) {
        BrowserControlsManager manager = activity.getBrowserControlsManager();
        if (manager == null) return;
        sControlsObserver =
                new Observer() {
                    @Override
                    public void onControlsOffsetChanged(
                            int topOffset,
                            int topControlsMinHeightOffset,
                            boolean topControlsMinHeightChanged,
                            int bottomOffset,
                            int bottomControlsMinHeightOffset,
                            boolean bottomControlsMinHeightChanged,
                            boolean requestNewFrame,
                            boolean isVisibilityForced) {
                        scheduleEnforce();
                    }

                    @Override
                    public void onTopControlsHeightChanged(
                            int topControlsHeight, int topControlsMinHeight) {
                        scheduleEnforce();
                    }

                    @Override
                    public void onBottomControlsHeightChanged(
                            int bottomControlsHeight, int bottomControlsMinHeight) {
                        scheduleEnforce();
                    }
                };
        manager.addObserver(sControlsObserver);
    }

    /** 约束是 HIDDEN 但工具栏（顶部或底部）没有完全收起。 */
    private static boolean controlsLeaking(BrowserControlsManager manager) {
        if (controlsConstraint() != BrowserControlsState.HIDDEN) return false;
        int topHidden = manager.getTopControlsMinHeight() - manager.getTopControlsHeight();
        int bottomHidden = manager.getBottomControlsHeight() - manager.getBottomControlsMinHeight();
        return manager.getTopControlOffset() > topHidden
                || manager.getBottomControlOffset() < bottomHidden;
    }

    private static void scheduleEnforce() {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null || sEnforcePending) return;
        BrowserControlsManager manager = activity.getBrowserControlsManager();
        if (manager == null || !controlsLeaking(manager)) return;
        sEnforcePending = true;
        ThreadUtils.postOnUiThreadDelayed(
                () -> {
                    sEnforcePending = false;
                    ChromeTabbedActivity current = sActivity;
                    if (current == null) return;
                    BrowserControlsManager m = current.getBrowserControlsManager();
                    if (m == null || !controlsLeaking(m)) return;
                    applyControls(true);
                },
                ENFORCE_DELAY_MS);
    }

    // ---------------------------------------------------------------- 挂接

    /** ChromeTabbedActivity.finishNativeInitialization() 之后（UI 线程）直接调入。 */
    public static void attach(ChromeTabbedActivity activity) {
        ThreadUtils.assertOnUiThread();
        if (activity.isDestroyed() || activity.isFinishing()) return;
        if (!isEnabled()) return;
        if (sActivity == activity && sNav != null) {
            refresh();
            MusePage.bringTailnetOnline();
            LemurXWelcome.maybeShow(activity);
            return;
        }
        detach();
        sActivity = activity;
        LemurXChromeHost.setNewTabUrl(homeUrl());
        try {
            mountNav(activity);
        } catch (Exception e) {
            logi("mount nav", e.getMessage());
        }
        try {
            observeTabs(activity);
        } catch (Exception e) {
            logi("tab observer", e.getMessage());
        }
        try {
            observeLayout(activity);
        } catch (Exception e) {
            logi("layout observer", e.getMessage());
        }
        try {
            observeOmnibox(activity);
        } catch (Exception e) {
            logi("omnibox observer", e.getMessage());
        }
        try {
            observeControls(activity);
        } catch (Exception e) {
            logi("controls observer", e.getMessage());
        }
        // 返回键：覆盖层开着就先收覆盖层，不让下面的网页后退。
        BackPressManager.setLemurXConsoleInterceptor(
                LemurXConsoleShell::consumeBack, LemurXConsoleShell::overlayVisible);
        sDestroyObserver =
                () -> {
                    if (sActivity == activity) detach();
                };
        sStartObserver =
                new StartStopWithNativeObserver() {
                    @Override
                    public void onStartWithNative() {
                        MusePage.bringTailnetOnline();
                    }

                    @Override
                    public void onStopWithNative() {}
                };
        try {
            activity.getLifecycleDispatcher().register(sDestroyObserver);
            activity.getLifecycleDispatcher().register(sStartObserver);
        } catch (Exception e) {
            logi("lifecycle", e.getMessage());
        }
        applyControls(false);
        refresh();
        MusePage.bringTailnetOnline();
        LemurXWelcome.maybeShow(activity);
        LemurXKillSwitch.attach(activity);
        LemurXAppUpdate.attach(activity);
    }

    /** 窗口销毁或重新挂接前：撤掉所有观察者和视图。 */
    private static void detach() {
        MusePage.dropDrawerReturn();
        ChromeTabbedActivity activity = sActivity;
        if (activity != null) LemurXKillSwitch.detach(activity);
        if (activity != null) LemurXAppUpdate.detach(activity);
        if (sChromeAnim != null) {
            sChromeAnim.cancel();
            sChromeAnim = null;
        }
        if (sTabObserver != null) {
            try {
                sTabObserver.destroy();
            } catch (Exception e) {
                // 旧窗口可能已销毁
            }
            sTabObserver = null;
        }
        if (sTabCountObserver != null && activity != null && activity.areTabModelsInitialized()) {
            try {
                for (TabModel model : activity.getTabModelSelector().getModels()) {
                    model.removeObserver(sTabCountObserver);
                }
            } catch (Exception e) {
                // 旧窗口可能已销毁
            }
        }
        sTabCountObserver = null;
        sTabCount = null;
        if (activity != null && sActivityTabObserver != null) {
            try {
                activity.getActivityTabProvider().asObservable().removeObserver(sActivityTabObserver);
            } catch (Exception e) {
                // 同上
            }
        }
        sActivityTabObserver = null;
        if (sLayoutProvider != null && sLayoutObserver != null) {
            try {
                sLayoutProvider.removeObserver(sLayoutObserver);
            } catch (Exception e) {
                // 同上
            }
        }
        sLayoutProvider = null;
        sLayoutObserver = null;
        if (sOmniboxStub != null && sOmniboxListener != null) {
            try {
                sOmniboxStub.removeUrlFocusChangeListener(sOmniboxListener);
            } catch (Exception e) {
                // 同上
            }
        }
        sOmniboxStub = null;
        sOmniboxListener = null;
        if (activity != null && sDestroyObserver != null) {
            try {
                activity.getLifecycleDispatcher().unregister(sDestroyObserver);
            } catch (Exception e) {
                // 同上
            }
        }
        sDestroyObserver = null;
        if (activity != null && sStartObserver != null) {
            try {
                activity.getLifecycleDispatcher().unregister(sStartObserver);
            } catch (Exception e) {
                // 同上
            }
        }
        sStartObserver = null;
        if (activity != null && sControlsObserver != null) {
            try {
                activity.getBrowserControlsManager().removeObserver(sControlsObserver);
            } catch (Exception e) {
                // 同上
            }
        }
        sControlsObserver = null;
        sEnforcePending = false;
        if (sNav != null && sNav.getParent() instanceof ViewGroup) {
            try {
                ((ViewGroup) sNav.getParent()).removeView(sNav);
            } catch (Exception e) {
                // 同上
            }
        }
        sNav = null;
        sPipChrome = false;
        releasePagePin();
        if (sPipCard != null && sPipCard.getParent() instanceof ViewGroup) {
            try {
                ((ViewGroup) sPipCard.getParent()).removeView(sPipCard);
            } catch (Exception e) {
                // 旧窗口可能已销毁
            }
        }
        sPipCard = null;
        LemurXWelcome.detach();
        BackPressManager.setLemurXConsoleInterceptor(null, null);
        detachOverlayView();
        for (MusePage page : sOverlays.values()) {
            try {
                page.destroy();
            } catch (Exception e) {
                // 同上
            }
        }
        sOverlays.clear();
        sOverlaySection.clear();
        if (sOverlayHost != null && sOverlayHost.getParent() instanceof ViewGroup) {
            try {
                ((ViewGroup) sOverlayHost.getParent()).removeView(sOverlayHost);
            } catch (Exception e) {
                // 同上
            }
        }
        concealCompositorForIme(false);
        sOverlayHost = null;
        if (sProgress != null && sProgress.getParent() instanceof ViewGroup) {
            try {
                ((ViewGroup) sProgress.getParent()).removeView(sProgress);
            } catch (Exception e) {
                // 同上
            }
        }
        sProgress = null;
        sProgressTabId = -1;
        detachGesture();
        MusePage.destroyAll();
        sWebCollapsed = false;
        sWebModeTabId = -1;
        sCapturing = false;
        sSectionCells = null;
        sTabsCell = null;
        sMenuCell = null;
        sExtensionsCell = null;
        sHasExtensions = false;
        sExtensionsBarOpen = false;
        sBackCell = null;
        sForwardCell = null;
        sPlace = CELL_WEB;
        sTabCount = null;
        sWebChrome = false;
        sChatTracksChrome = false;
        sNavSuppressors.clear();
        if (activity != null && sHidingToken != TokenHolder.INVALID_TOKEN) {
            try {
                activity.getBrowserControlsManager().releaseAndroidControlsHidingToken(sHidingToken);
            } catch (Exception e) {
                // 同上
            }
        }
        sHidingToken = TokenHolder.INVALID_TOKEN;
        sOmniboxOpen = false;
        sSearchHandoff = false;
        sNavHeight = 0;
        sLayout = LayoutType.BROWSING;
        sActivity = null;
    }

    private static void observeTabs(ChromeTabbedActivity activity) {
        if (!activity.areTabModelsInitialized()) return;
        TabModelSelector selector = activity.getTabModelSelector();
        sTabObserver =
                new TabModelSelectorTabObserver(selector) {
                    @Override
                    public void onDidStartNavigationInPrimaryMainFrame(
                            Tab tab, NavigationHandle navigation) {
                        try {
                            interceptSurface(tab, navigation);
                        } catch (Throwable e) {
                            logi("intercept", e.toString());
                        }
                    }

                    @Override
                    public void onUrlUpdated(Tab tab) {
                        rememberWebUrl(tab, tab.getUrl());
                        refreshIfForeground(tab);
                    }

                    @Override
                    public void onPageLoadStarted(Tab tab, GURL url) {
                        rememberWebUrl(tab, url);
                        // 换了一页：导航栏重新出现。
                        sWebCollapsed = false;
                        // 标签页自己导航了（提交搜索、输入地址…）：用户要看的是网页，收起覆盖层。
                        if (sOverlaySection.remove(tab.getId()) != null) sWebCollapsed = false;
                        refreshIfForeground(tab);
                        showProgress(tab, 0.05f);
                    }

                    @Override
                    public void onLoadProgressChanged(Tab tab, float progress) {
                        showProgress(tab, progress);
                    }

                    @Override
                    public void onPageLoadFinished(Tab tab, GURL url) {
                        finishProgress(tab);
                        // 官方商店是桌面页。加载完后按手机宽度收一下，安装按钮仍走商店自己的接口。
                        LemurXWebStore.fit(tab);
                    }

                    @Override
                    public void onPageLoadFailed(Tab tab, int errorCode) {
                        finishProgress(tab);
                    }

                    @Override
                    public void onCrash(Tab tab) {
                        finishProgress(tab);
                    }

                    @Override
                    public void onHidden(Tab tab, int type) {
                        if (tab.getId() == sProgressTabId) hideProgress();
                    }

                    @Override
                    public void onDestroyed(Tab tab) {
                        forgetTabThread(tab.getId());
                        sTabWebUrls.remove(tab.getId());
                        sTabWebTitles.remove(tab.getId());
                        sTabShots.remove(tab.getId());
                        dropCard(tab.getId());
                        sOverlaySection.remove(tab.getId());
                        MusePage overlay = sOverlays.remove(tab.getId());
                        if (overlay != null) {
                            if (sOverlayAttached == overlay) detachOverlayView();
                            try {
                                overlay.destroy();
                            } catch (Exception e) {
                                logi("overlay destroy", e.getMessage());
                            }
                        }
                        MusePage.onTabDestroyed(tab.getId());
                    }

                    @Override
                    public void onDidFinishNavigationInPrimaryMainFrame(
                            Tab tab, NavigationHandle navigation) {
                        refreshIfForeground(tab);
                    }

                    @Override
                    public void onShown(Tab tab, int type) {
                        refreshIfForeground(tab);
                    }

                    @Override
                    public void onContentChanged(Tab tab) {
                        refreshIfForeground(tab);
                    }
                };
        sTabCountObserver =
                new TabModelObserver() {
                    @Override
                    public void didAddTab(
                            Tab tab,
                            @TabLaunchType int type,
                            @TabCreationState int creationState,
                            boolean markedForSelection) {
                        updateTabCount();
                    }

                    @Override
                    public void tabRemoved(Tab tab) {
                        updateTabCount();
                    }

                    @Override
                    public void tabClosureUndone(Tab tab) {
                        updateTabCount();
                    }

                    @Override
                    public void allTabsClosureCommitted(boolean isIncognito) {
                        updateTabCount();
                    }

                    @Override
                    public void didSelectTab(Tab tab, @TabSelectionType int type, int lastId) {
                        updateTabCount();
                    }
                };
        for (TabModel model : selector.getModels()) model.addObserver(sTabCountObserver);
        updateTabCount();
        sActivityTabObserver = tab -> refresh();
        activity.getActivityTabProvider().asObservable().addSyncObserver(sActivityTabObserver);
    }

    private static void refreshIfForeground(@Nullable Tab tab) {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null || tab == null) return;
        try {
            if (activity.getActivityTab() == tab) refresh();
        } catch (Throwable e) {
            logi("refresh", e.toString());
        }
    }

    private static void observeLayout(ChromeTabbedActivity activity) {
        activity.getLayoutStateProviderSupplier()
                .onAvailable(
                        provider -> {
                            if (sActivity != activity) return;
                            sLayoutProvider = provider;
                            sLayoutObserver =
                                    new LayoutStateObserver() {
                                        @Override
                                        public void onStartedShowing(@LayoutType int type) {
                                            sLayout = type;
                                            refresh();
                                        }

                                        @Override
                                        public void onFinishedShowing(@LayoutType int type) {
                                            sLayout = type;
                                            refresh();
                                        }
                                    };
                            provider.addObserver(sLayoutObserver);
                            try {
                                sLayout = provider.getActiveLayoutType();
                            } catch (Exception e) {
                                sLayout = LayoutType.BROWSING;
                            }
                            refresh();
                        });
    }

    private static void observeOmnibox(ChromeTabbedActivity activity) {
        ToolbarManager toolbar = activity.getToolbarManager();
        OmniboxStub stub = toolbar == null ? null : toolbar.getOmniboxStub();
        if (stub == null) return;
        sOmniboxListener =
                hasFocus -> {
                    if (hasFocus) {
                        // 不管是谁聚焦的地址栏（搜索按钮、键盘快捷键…），都按"正在用地址栏"处理。
                        sOmniboxOpen = true;
                        sSearchHandoff = false;
                    } else {
                        // 没搜就退出：把工具栏收回去。提交了搜索的话导航会接着刷新。
                        sOmniboxOpen = false;
                        sSearchHandoff = false;
                        scheduleSearchReturn();
                    }
                    applyControls(true);
                    refresh();
                };
        sOmniboxStub = stub;
        stub.addUrlFocusChangeListener(sOmniboxListener);
    }

    /**
     * 地址栏退出后：如果是从原生页面点搜索过来的，而且标签页还停在那张网页上（没提交搜索），
     * 就回到出发的原生页面——下一条历史正好是它就前进（保留会话视图），不是就重新加载。
     */
    private static void scheduleSearchReturn() {
        final String returnUrl = sSearchReturnUrl;
        final String webUrl = sSearchWebUrl;
        final int tabId = sSearchReturnTabId;
        sSearchReturnUrl = null;
        sSearchWebUrl = null;
        sSearchReturnTabId = -1;
        if (returnUrl == null || webUrl == null) return;
        ThreadUtils.postOnUiThreadDelayed(
                () -> {
                    ChromeTabbedActivity activity = sActivity;
                    if (activity == null || sOmniboxOpen) return;
                    try {
                        Tab current = activity.getActivityTab();
                        if (current == null || current.getId() != tabId || current.isDestroyed()) {
                            return;
                        }
                        // 提交了搜索 / 输入了地址：标签页已经不在出发时那张网页上了。
                        if (!webUrl.equals(current.getUrl().getSpec())) return;
                        if (nextEntryIs(current, returnUrl)) {
                            current.goForward();
                        } else {
                            current.loadUrl(new LoadUrlParams(returnUrl));
                        }
                    } catch (Exception e) {
                        logi("searchReturn", e.getMessage());
                    }
                },
                SEARCH_RETURN_DELAY_MS);
    }

    // ---------------------------------------------------------------- 网页里的搜索 / 标签页按钮

    /**
     * 网页端 requestBrowserSurface() 用 location.assign 跳到 /__lemurx/omnibox 或 /__lemurx/tabs。
     * 在导航刚开始、还没提交的时候就 stop 掉（不会留下历史记录），然后做对应的原生动作。
     *
     * <p>stop 必须另起一个任务：这个回调跑在 WebContentsImpl::DidStartNavigation 的观察者遍历里，
     * 在里面同步 Stop 会把 NavigationRequest 当场销毁，后面的观察者（TabStatsTracker 等）拿到的
     * 就是已经释放的 NavigationHandle，浏览器进程直接 SIGSEGV。
     */
    private static void interceptSurface(Tab tab, NavigationHandle navigation) {
        if (!navigation.isInPrimaryMainFrame() || navigation.isSameDocument()) return;
        GURL url = navigation.getUrl();
        if (url == null || !url.isValid()) return;
        String path = url.getPath();
        boolean omnibox = SURFACE_OMNIBOX.equals(path);
        boolean tabs = SURFACE_TABS.equals(path);
        if (!omnibox && !tabs) return;
        ThreadUtils.postOnUiThread(
                () -> {
                    try {
                        if (tab.getWebContents() != null && tab.isLoading()) tab.stopLoading();
                    } catch (Exception e) {
                        logi("stop surface navigation", e.getMessage());
                    }
                    if (omnibox) {
                        openSearch();
                    } else {
                        openTabSwitcher();
                    }
                });
    }

    // ---------------------------------------------------------------- 动作

    /** 退出登录：停掉正在跑的 Agent，放开屏幕锁。界面仍留在对话，输入框改回网址和搜索。 */
    public static void onSignedOut() {
        MusePage.stopAgentWork();
        LemurXDeviceBridge.releaseScreen();
        MusePage.applyAccountMode();
    }

    /** 回到对话：等同于按导航栏的"对话"格。 */
    public static void goHome() {
        goChat();
    }

    private static int sPresentTries;

    /**
     * 冷启动回到上次停下的地方。上次是盖在网页上的对话，就还盖那一轮，不新开一轮。
     * 上次就是网页或原生对话，Chrome 恢复的那一页已经是它，这里不再改。
     */
    public static void presentAgent() {
        sPresentTries = 0;
        presentAgentSoon();
    }

    private static void presentAgentSoon() {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null || sPresentTries++ > 40) return;
        if (!tabsReady()) {
            ThreadUtils.postOnUiThreadDelayed(LemurXConsoleShell::presentAgentSoon, 50);
            return;
        }
        try {
            if (!MuseUrls.SECTION_CHAT.equals(prefs().getString(KEY_LAST_SECTION, ""))) return;
            Tab current = activity.getActivityTab();
            if (current == null) return;
            String savedUrl = prefs().getString(KEY_LAST_URL, "");
            String currentUrl = current.getUrl().getSpec();
            if (!TextUtils.isEmpty(savedUrl) && !savedUrl.equals(currentUrl)) return;
            if (showingChat(current)) return;
            String thread = prefs().getString(KEY_LAST_THREAD, "");
            if (!TextUtils.isEmpty(thread)) setTabThread(current, thread);
            showOverlay(current, MuseUrls.SECTION_CHAT);
        } catch (Exception e) {
            logi("presentAgent", e.getMessage());
        }
    }

    /** 标签页恢复完、前台那一页已经在了。启动封面用它决定能不能揭开。 */
    public static boolean tabsReady() {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null || !activity.areTabModelsInitialized()) return false;
        try {
            return activity.getTabModelSelector().isTabStateInitialized()
                    && activity.getActivityTab() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 上次停在对话上时，要等对话画出来再揭封面。上次是网页，恢复完就可以揭。 */
    public static boolean startupCoverReady(boolean chatVisible) {
        if (!tabsReady()) return false;
        return !lastPlaceWasChat() || chatVisible;
    }

    private static boolean lastPlaceWasChat() {
        try {
            if (MuseUrls.SECTION_CHAT.equals(prefs().getString(KEY_LAST_SECTION, ""))) return true;
            return MuseUrls.SECTION_CHAT.equals(
                    MuseUrls.sectionOf(prefs().getString(KEY_LAST_URL, "")));
        } catch (Exception e) {
            return false;
        }
    }

    /** 记住现在这一页。恢复还没完成时不写，免得临时的新标签盖掉上次的记录。 */
    private static void rememberForeground(Tab tab, @Nullable String overlaySection) {
        if (!tabsReady()) return;
        try {
            String url = tab.getUrl().getSpec();
            if (TextUtils.isEmpty(url) || "about:blank".equals(url)) return;
            String section = overlaySection;
            if (section == null && MuseUrls.SECTION_CHAT.equals(MuseUrls.sectionOf(url))) {
                section = MuseUrls.SECTION_CHAT;
            }
            if (section == null) section = "";
            String thread = threadOfTab(tab);
            if (thread == null) thread = "";
            prefs().edit()
                    .putString(KEY_LAST_URL, url)
                    .putString(KEY_LAST_SECTION, section)
                    .putString(KEY_LAST_THREAD, thread)
                    .apply();
        } catch (Exception e) {
            logi("rememberForeground", e.getMessage());
        }
    }

    /**
     * 搜索开着时，点在地址栏和建议以外的空白：收起键盘并退出搜索。
     *
     * @return true 这次触摸已经处理，不要再交给下面的网页。
     */
    public static boolean consumeSearchBlank(MotionEvent event) {
        if (!sOmniboxOpen) {
            sSearchBlank = false;
            return false;
        }
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            sSearchBlank = !touchInSearchChrome(activity, event);
            sSearchX = event.getRawX();
            sSearchY = event.getRawY();
            return sSearchBlank;
        }
        if (!sSearchBlank) return false;
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getRawX() - sSearchX;
            float dy = event.getRawY() - sSearchY;
            int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
            if (dx * dx + dy * dy > (float) slop * slop) sSearchBlank = false;
            return sSearchBlank;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean close = action == MotionEvent.ACTION_UP && sSearchBlank;
            sSearchBlank = false;
            if (close && sOmniboxStub != null) {
                try {
                    sOmniboxStub.onScrimClicked();
                } catch (Exception e) {
                    logi("searchBlank", e.getMessage());
                }
            }
            return true;
        }
        return sSearchBlank;
    }

    private static boolean touchInSearchChrome(ChromeTabbedActivity activity, MotionEvent event) {
        if (touchInView(activity.findViewById(R.id.control_container), event)) return true;
        return touchInView(activity.findViewById(R.id.omnibox_suggestions_dropdown), event);
    }

    private static boolean touchInView(@Nullable View view, MotionEvent event) {
        if (view == null || view.getVisibility() != View.VISIBLE || view.getWidth() == 0) return false;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        float x = event.getRawX();
        float y = event.getRawY();
        return x >= loc[0] && x < loc[0] + view.getWidth()
                && y >= loc[1] && y < loc[1] + view.getHeight();
    }

    /**
     * "对话"格：这个标签页自己的会话。
     *
     * <ul>
     *   <li>前台是原生页面（社区页）：就在这个标签页里切，替换当前历史条目（分区切换不堆返回栈）。
     *   <li>前台是普通网页：在同一个标签页里打开它的会话（没有就新开一轮），压一条历史，返回键回网页。
     * </ul>
     */
    public static void goChat() {
        MusePage.dismissCovers();
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return;
        try {
            if (!activity.areTabModelsInitialized()) return;
            Tab current = activity.getActivityTab();
            if (current == null) return;
            if (MuseUrls.isMuse(current.getUrl().getSpec())) {
                loadSection(current, MuseUrls.SECTION_CHAT);
                return;
            }
            rememberWebUrl(current, current.getUrl());
            captureForOverlay(current);
            showOverlay(current, MuseUrls.SECTION_CHAT);
        } catch (Exception e) {
            logi("goChat", e.getMessage());
        }
    }

    /** 社区是盖在所有标签页上的一层，不改当前标签的地址。 */
    public static void goCommunity() {
        if (sActivity == null) return;
        if (!MusePage.requireAccount()) return;
        MusePage.closeFiles();
        MusePage.closeArchive();
        MusePage.showCommunity(sActivity);
        refresh();
    }

    /** 底部导航最右一格：侧边栏。对话页和网页都走这一格。 */
    public static void openSidebar() {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return;
        MusePage.dismissCovers();
        try {
            if (!activity.areTabModelsInitialized()) return;
            Tab current = activity.getActivityTab();
            if (current == null) return;
            // 眼前就是对话页时，抽屉开在这一页上。另盖一层的话，档案会画到看不见的页面上。
            Object nativePage = current.getNativePage();
            if (nativePage instanceof MusePage) {
                ((MusePage) nativePage).openDrawer();
                return;
            }
            MusePage page = sOverlayAttached != null ? sOverlayAttached : sOverlays.get(current.getId());
            if (page == null) {
                String thread = tabThread(current);
                if (thread == null) {
                    thread = MuseUrls.threadOf(MuseUrls.newChatUrl());
                    setTabThread(current, thread);
                }
                page = MusePage.overlay(
                        current, activity, LemurXConsoleShell::openInNewTab, MuseUrls.chatUrl(thread));
                sOverlays.put(current.getId(), page);
            }
            page.openDrawer();
        } catch (Exception e) {
            logi("openSidebar", e.getMessage());
        }
    }

    /** 网页历史后退。不用系统返回键，只走标签页自己的历史。 */
    public static void goHistoryBack() {
        MusePage.dismissCovers();
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return;
        try {
            Tab current = activity.getActivityTab();
            if (current != null && current.canGoBack()) {
                current.goBack();
                refresh();
            }
        } catch (Exception e) {
            logi("goHistoryBack", e.getMessage());
        }
    }

    /** 网页历史前进。 */
    public static void goHistoryForward() {
        MusePage.dismissCovers();
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return;
        try {
            Tab current = activity.getActivityTab();
            if (current != null && current.canGoForward()) {
                current.goForward();
                refresh();
            }
        } catch (Exception e) {
            logi("goHistoryForward", e.getMessage());
        }
    }

    /**
     * Agent 做完了，或者要用户给验证码：回到这个标签的对话，不再停在锁住的网页上。
     * 必须在 UI 线程调用。
     */
    public static void returnToChat(int tabId) {
        ThreadUtils.assertOnUiThread();
        ChromeTabbedActivity activity = sActivity;
        if (activity == null || !activity.areTabModelsInitialized()) return;
        try {
            Tab tab = activity.getTabModelSelector().getModel(false).getTabById(tabId);
            if (tab == null) tab = activity.getTabModelSelector().getModel(true).getTabById(tabId);
            if (tab == null) tab = activity.getActivityTab();
            if (tab == null) return;
            TabModelUtils.selectTabById(activity.getTabModelSelector(), tab.getId(),
                    TabSelectionType.FROM_USER);
            showOverlay(tab, MuseUrls.SECTION_CHAT);
        } catch (Exception e) {
            logi("returnToChat", e.getMessage());
        }
    }

    /**
     * Agent 要操作网页：收起当前标签上的对话覆盖层，让用户看见页面。必须在 UI 线程调用。
     */
    public static void revealWeb() {
        ThreadUtils.assertOnUiThread();
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return;
        try {
            Tab current = activity.getActivityTab();
            if (current == null) return;
            if (sOverlaySection.remove(current.getId()) != null) {
                sWebCollapsed = false;
                refresh();
            }
        } catch (Exception e) {
            logi("revealWeb", e.getMessage());
        }
    }

    /**
     * "网页"格：回到这个标签页正在看的网页。上一条历史正好是它就直接后退（保留滚动位置和页面
     * 状态），不是就重新加载它；这个标签页还没看过网页就唤起地址栏。普通网页上是空操作。
     */
    public static void goWeb() {
        MusePage.dismissCovers();
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return;
        try {
            if (!activity.areTabModelsInitialized()) return;
            Tab current = activity.getActivityTab();
            if (current == null) return;
            if (!MuseUrls.isMuse(current.getUrl().getSpec())) {
                // 网页上盖着 Agent：拿掉覆盖层就是网页，什么都不用加载。
                if (sOverlaySection.remove(current.getId()) != null) {
                    sWebCollapsed = false;
                    refresh();
                }
                return;
            }
            String web = tabWebUrl(current);
            if (web == null) {
                openSearch();
                return;
            }
            if (previousEntryIs(current, web)) {
                current.goBack();
            } else {
                current.loadUrl(new LoadUrlParams(web));
            }
        } catch (Exception e) {
            logi("goWeb", e.getMessage());
        }
    }

    /** 上一条历史条目是不是这个地址。 */
    private static boolean previousEntryIs(Tab tab, String url) {
        return entryAtOffsetIs(tab, url, -1);
    }

    /** 下一条历史条目是不是这个地址。 */
    private static boolean nextEntryIs(Tab tab, String url) {
        return entryAtOffsetIs(tab, url, 1);
    }

    private static boolean entryAtOffsetIs(Tab tab, String url, int offset) {
        try {
            WebContents contents = tab.getWebContents();
            if (contents == null) return false;
            NavigationController controller = contents.getNavigationController();
            NavigationHistory history = controller.getNavigationHistory();
            if (history == null) return false;
            int index = history.getCurrentEntryIndex() + offset;
            if (index < 0 || index >= history.getEntryCount()) return false;
            NavigationEntry entry = history.getEntryAtIndex(index);
            return entry != null && url.equals(entry.getUrl().getSpec());
        } catch (Exception e) {
            return false;
        }
    }

    /** 盖上 Agent 之前先截一张当前网页的图记在标签页上（异步，不阻塞覆盖层出现）。 */
    private static void captureForOverlay(Tab tab) {
        if (sCapturing) return;
        String webUrl = tab.getUrl().getSpec();
        sCapturing = true;
        captureWebContents(tab, webUrl, () -> sCapturing = false);
    }

    /**
     * 让 Chromium 自己把当前网页画面读回来（RenderWidgetHostView::CopyFromSurface），缩小、
     * 存成 JPEG 文件，再读进内存转 Base64 记到标签页上。
     *
     * <p>网页由合成器画在 SurfaceView / SurfaceControl 那一层，对窗口做 PixelCopy 只会得到
     * 窗口上挖出来的黑洞，对 SurfaceView 做 PixelCopy 在 SurfaceControl 模式下也拿不到
     * （ERROR_SOURCE_INVALID），所以走合成器自己的读回通道。
     */
    private static void captureWebContents(Tab tab, String webUrl, Runnable done) {
        try {
            WebContents contents = tab.getWebContents();
            RenderWidgetHostView rwhv = contents != null ? contents.getRenderWidgetHostView() : null;
            View view = tab.getContentView();
            if (rwhv == null || view == null || view.getWidth() == 0 || view.getHeight() == 0) {
                logi("captureWebContents", "no RenderWidgetHostView / content view");
                done.run();
                return;
            }
            int width = view.getWidth();
            int height = view.getHeight();
            int longest = Math.max(width, height);
            float scale = longest > SHOT_MAX_SIDE ? (float) SHOT_MAX_SIDE / longest : 1f;
            int outW = Math.max(1, Math.round(width * scale));
            int outH = Math.max(1, Math.round(height * scale));
            File dir = new File(ContextUtils.getApplicationContext().getCacheDir(), SHOT_DIR);
            rwhv.writeContentBitmapToDiskAsync(
                    outW,
                    outH,
                    dir.getAbsolutePath(),
                    path -> {
                        if (TextUtils.isEmpty(path)) {
                            logi("captureWebContents", "readback failed");
                            done.run();
                            return;
                        }
                        PostTask.postTask(
                                TaskTraits.USER_VISIBLE_MAY_BLOCK,
                                () -> {
                                    String base64 = null;
                                    int size = 0;
                                    try {
                                        File file = new File(path);
                                        byte[] bytes = Files.readAllBytes(file.toPath());
                                        size = bytes.length;
                                        base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                                        file.delete();
                                    } catch (Exception e) {
                                        logi("captureWebContents", e.getMessage());
                                    }
                                    final String shot = base64;
                                    final int bytes = size;
                                    ThreadUtils.postOnUiThread(
                                            () -> {
                                                if (shot != null) {
                                                    sTabShots.put(tab.getId(), new Shot(webUrl, shot));
                                                    logi(
                                                            "captureWebContents",
                                                            outW + "x" + outH + " jpeg " + bytes
                                                                    + " bytes");
                                                }
                                                done.run();
                                            });
                                });
                    });
        } catch (Exception e) {
            logi("captureWebContents", e.getMessage());
            done.run();
        }
    }

    /** 分区对应的原生页面地址；对话分区是一轮新会话。 */
    private static String sectionUrl(String section) {
        if (MuseUrls.SECTION_COMMUNITY.equals(section)) return MuseUrls.COMMUNITY_URL;
        return MuseUrls.newChatUrl();
    }

    /**
     * 同一原生标签页里切分区（替换当前历史条目）。对话分区用这个标签页记着的会话 id（没有才新开），
     * 其余分区是固定地址。
     */
    private static void loadSection(Tab tab, String section) {
        String url = tab.getUrl().getSpec();
        // 已经在这个分区就什么都不做：同地址再加载会变成重载。
        if (section.equals(MuseUrls.sectionOf(url))) return;
        String target;
        if (MuseUrls.SECTION_CHAT.equals(section)) {
            String thread = tabThread(tab);
            if (thread == null && tab.getNativePage() instanceof MusePage) {
                thread = ((MusePage) tab.getNativePage()).threadId();
            }
            target = thread != null ? MuseUrls.chatUrl(thread) : MuseUrls.newChatUrl();
        } else {
            target = sectionUrl(section);
        }
        LoadUrlParams params = new LoadUrlParams(target);
        params.setShouldReplaceCurrentEntry(true);
        tab.loadUrl(params);
    }

    // ---------------------------------------------------------------- 给原生页面用

    /** 所有停在对话页的标签页（普通模型），活动页用来把任务对应回标签页。 */
    public static List<Tab> chatTabs() {
        List<Tab> tabs = new ArrayList<>();
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return tabs;
        try {
            if (!activity.areTabModelsInitialized()) return tabs;
            TabModel model = activity.getTabModelSelector().getModel(false);
            for (int i = 0; i < model.getCount(); i++) {
                Tab tab = model.getTabAt(i);
                if (tab != null && threadOfTab(tab) != null) tabs.add(tab);
            }
        } catch (Exception e) {
            logi("chatTabs", e.getMessage());
        }
        return tabs;
    }

    /** 这个标签页的会话 id；还没有就返回 null。 */
    public static @Nullable String tabThread(Tab tab) {
        ensureTabThreadsLoaded();
        return sTabThreads.get(tab.getId());
    }

    /** 这个标签页的会话 id：记录里的，或者它正停在的对话页地址里的；都没有返回 null。 */
    public static @Nullable String threadOfTab(Tab tab) {
        String thread = tabThread(tab);
        if (thread != null) return thread;
        try {
            return MuseUrls.threadOf(tab.getUrl());
        } catch (Exception e) {
            return null;
        }
    }

    /** 原生页面解析出会话 id 后记到标签页上。进程被杀后靠 {@link #KEY_TAB_THREADS} 找回来。 */
    public static void setTabThread(Tab tab, String thread) {
        if (TextUtils.isEmpty(thread)) return;
        ensureTabThreadsLoaded();
        String previous = sTabThreads.put(tab.getId(), thread);
        if (!thread.equals(previous)) persistTabThreads();
    }

    /** 标签页关了：内存和落盘都忘掉，免得 Chrome 复用这个 id 时串到旧对话。 */
    private static void forgetTabThread(int tabId) {
        ensureTabThreadsLoaded();
        if (sTabThreads.remove(tabId) != null) persistTabThreads();
    }

    private static void ensureTabThreadsLoaded() {
        if (sTabThreadsLoaded) return;
        sTabThreadsLoaded = true;
        String raw = prefs().getString(KEY_TAB_THREADS, null);
        if (TextUtils.isEmpty(raw)) return;
        try {
            JSONObject o = new JSONObject(raw);
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String thread = o.optString(key, "");
                if (TextUtils.isEmpty(thread) || !thread.matches("[A-Za-z0-9-]{1,64}")) continue;
                try {
                    sTabThreads.put(Integer.parseInt(key), thread);
                } catch (NumberFormatException e) {
                    // 旧键坏了就跳过
                }
            }
        } catch (JSONException e) {
            logi("tabThreads", e.getMessage());
        }
    }

    private static void persistTabThreads() {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<Integer, String> e : sTabThreads.entrySet()) {
                o.put(String.valueOf(e.getKey()), e.getValue());
            }
        } catch (JSONException e) {
            return;
        }
        prefs().edit().putString(KEY_TAB_THREADS, o.toString()).apply();
    }

    /**
     * 这个标签页最近看过的普通网页；没看过返回 null。没记录（例如重启后恢复的标签页）时往历史
     * 里倒着找最近的一条 http(s) 条目并记下来。
     */
    public static @Nullable String tabWebUrl(Tab tab) {
        String remembered = sTabWebUrls.get(tab.getId());
        if (remembered != null) return remembered;
        try {
            WebContents contents = tab.getWebContents();
            if (contents == null) return null;
            NavigationHistory history = contents.getNavigationController().getNavigationHistory();
            if (history == null) return null;
            for (int i = history.getCurrentEntryIndex(); i >= 0; i--) {
                NavigationEntry entry = history.getEntryAtIndex(i);
                if (entry == null) continue;
                GURL url = entry.getUrl();
                if (!url.isValid()) continue;
                String scheme = url.getScheme();
                if (!"http".equals(scheme) && !"https".equals(scheme)) continue;
                if (isHome(url.getSpec())) continue;
                sTabWebUrls.put(tab.getId(), url.getSpec());
                return url.getSpec();
            }
        } catch (Exception e) {
            logi("tabWebUrl", e.getMessage());
        }
        return null;
    }

    /**
     * 这个标签页从网页切到对话 / 任务时截的网页图（JPEG，base64），没有返回 null。只在截图对应的
     * 网页还是这个标签页"正在看的网页"时才给，免得把早已离开的页面当成现状。
     */
    public static @Nullable String tabScreenshot(Tab tab) {
        Shot shot = sTabShots.get(tab.getId());
        if (shot == null) return null;
        String web = tabWebUrl(tab);
        if (web == null || !web.equals(shot.url)) return null;
        return shot.jpegBase64;
    }

    /**
     * 标签管理要的缩略图。盖着对话、社区或电脑时返回离开那一层的画面；看着网页时返回 null，
     * 让 Chromium 自己截网页。tab_ui 和这里不在同一个库，所以由 {@code TabContentManager} 反射进来。
     */
    public static @Nullable Bitmap tabCardBitmap(Tab tab, float scale) {
        if (tab == null) return null;
        if (showingChat(tab)) {
            rememberAttachedChat();
            Bitmap chat = sChatCards.get(tab.getId());
            if (chat != null && !chat.isRecycled()) return chat;
            Bitmap kept = sCards.get(tab.getId());
            if (kept != null && !kept.isRecycled()) return kept;
            View live = cardSource(tab);
            if (live != null) {
                Bitmap drawn = drawCard(live, cardScale(scale));
                if (drawn != null) {
                    stashChat(tab.getId(), drawn);
                    return drawn;
                }
            }
            return blankCard();
        }
        if (tab.getId() == sFrozenCardTab) {
            sFrozenCardTab = Tab.INVALID_TAB_ID;
            Bitmap frozen = sCards.get(tab.getId());
            if (frozen != null && !frozen.isRecycled()) return frozen;
        }
        View live = cardSource(tab);
        if (live != null) {
            Bitmap drawn = drawCard(live, cardScale(scale));
            if (drawn != null) {
                stashCard(tab.getId(), drawn);
                return drawn;
            }
        }
        if (sOverlaySection.containsKey(tab.getId())) {
            Bitmap kept = sCards.get(tab.getId());
            if (kept != null && !kept.isRecycled()) return kept;
            return blankCard();
        }
        dropCard(tab.getId());
        return null;
    }

    /** 打开标签管理之前，把眼前这一层留下来。社区和电脑马上会被收起。 */
    private static void freezeCard() {
        sFrozenCardTab = Tab.INVALID_TAB_ID;
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return;
        Tab tab;
        try {
            tab = activity.getActivityTab();
        } catch (Exception e) {
            return;
        }
        if (tab == null) return;
        View source = cardSource(tab);
        if (source == null) return;
        Bitmap bitmap = drawCard(source, cardScale(0f));
        if (bitmap == null) return;
        stashCard(tab.getId(), bitmap);
        sFrozenCardTab = tab.getId();
    }

    private static @Nullable View cardSource(Tab tab) {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return null;
        try {
            if (activity.getActivityTab() != tab) return null;
        } catch (Exception e) {
            return null;
        }
        View cover = MusePage.foregroundCover();
        if (cover != null && cover.getWidth() > 0 && cover.getVisibility() == View.VISIBLE) {
            return cover;
        }
        FrameLayout host = sOverlayHost;
        if (host != null
                && sOverlayAttached != null
                && host.getVisibility() == View.VISIBLE
                && host.getWidth() > 0) {
            // 截里面的对话页，不要把盖在外面的空边一起带上。
            if (host.getChildCount() > 0) return host.getChildAt(0);
            return host;
        }
        try {
            Object page = tab.getNativePage();
            if (page instanceof MusePage) {
                View view = ((MusePage) page).getView();
                if (view != null && view.getWidth() > 0) return view;
            }
        } catch (Exception e) {
            logi("cardSource", e.getMessage());
        }
        return null;
    }

    private static float cardScale(float requested) {
        if (requested > 0f && requested <= 1f) return requested;
        float density = android.content.res.Resources.getSystem().getDisplayMetrics().density;
        return density > 1.5f ? 1.5f / density : 1f;
    }

    private static @Nullable Bitmap drawCard(View view, float scale) {
        // 状态栏和底栏是页面自己留出的空边。卡片只留中间的内容，否则会像被裁短一截。
        int cropLeft = view.getPaddingLeft();
        int cropTop = view.getPaddingTop();
        int cropRight = view.getPaddingRight();
        int cropBottom = view.getPaddingBottom();
        int contentW = view.getWidth() - cropLeft - cropRight;
        int contentH = view.getHeight() - cropTop - cropBottom;
        if (contentW < view.getWidth() / 2 || contentH < view.getHeight() / 2) {
            cropLeft = 0;
            cropTop = 0;
            contentW = view.getWidth();
            contentH = view.getHeight();
        }
        int width = Math.max(1, Math.round(contentW * scale));
        int height = Math.max(1, Math.round(contentH * scale));
        if (width <= 1 || height <= 1) return null;
        try {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(MusePage.bg(view.getContext()));
            canvas.scale(scale, scale);
            canvas.translate(-cropLeft, -cropTop);
            canvas.clipRect(cropLeft, cropTop, cropLeft + contentW, cropTop + contentH);
            view.draw(canvas);
            return bitmap;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap blankCard() {
        Bitmap bitmap = Bitmap.createBitmap(48, 80, Bitmap.Config.ARGB_8888);
        ChromeTabbedActivity activity = sActivity;
        int color = activity == null ? 0xFFFFFFFF : MusePage.bg(activity);
        new Canvas(bitmap).drawColor(color);
        return bitmap;
    }

    private static void stashCard(int tabId, Bitmap bitmap) {
        Bitmap previous = sCards.put(tabId, bitmap);
        if (previous != null && previous != bitmap && !previous.isRecycled()) previous.recycle();
    }

    private static void dropCard(int tabId) {
        Bitmap previous = sCards.remove(tabId);
        if (previous != null && !previous.isRecycled()) previous.recycle();
        Bitmap chat = sChatCards.remove(tabId);
        if (chat != null && !chat.isRecycled()) chat.recycle();
        if (sFrozenCardTab == tabId) sFrozenCardTab = Tab.INVALID_TAB_ID;
    }

    /** 眼前这层是 Agent 对话时，记下它的画面，标签卡片不再用底下的网页。 */
    private static void rememberAttachedChat() {
        MusePage page = sOverlayAttached;
        FrameLayout host = sOverlayHost;
        if (page == null || host == null) return;
        if (!MuseUrls.SECTION_CHAT.equals(sOverlaySection.get(page.tabId()))) return;
        if (host.getVisibility() != View.VISIBLE || host.getWidth() <= 0 || host.getHeight() <= 0) {
            return;
        }
        View source = host.getChildCount() > 0 ? host.getChildAt(0) : host;
        Bitmap drawn = drawCard(source, cardScale(0f));
        if (drawn != null) stashChat(page.tabId(), drawn);
    }

    private static void stashChat(int tabId, Bitmap bitmap) {
        Bitmap previous = sChatCards.put(tabId, bitmap);
        if (previous != null && previous != bitmap && !previous.isRecycled()) previous.recycle();
    }

    /** 这个标签眼前是 Agent 对话（覆盖层或原生对话页），不是网页。 */
    private static boolean showingChat(Tab tab) {
        if (MuseUrls.SECTION_CHAT.equals(sOverlaySection.get(tab.getId()))) return true;
        try {
            GURL url = tab.getUrl();
            return url != null && MuseUrls.SECTION_CHAT.equals(MuseUrls.sectionOf(url.getSpec()));
        } catch (Exception e) {
            return false;
        }
    }

    /** 只记 http(s) 网页；原生页面、about:blank、chrome:// 之类都不算"网页"。 */
    private static void rememberWebUrl(Tab tab, @Nullable GURL url) {
        try {
            if (url == null || !url.isValid()) return;
            String scheme = url.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) return;
            String spec = url.getSpec();
            // 旧网页版首页由网页自己画栏，不当作"正在看的网页"。
            if (isHome(spec)) return;
            sTabWebUrls.put(tab.getId(), spec);
            String title = tab.getTitle();
            if (!TextUtils.isEmpty(title) && !MuseUrls.isMuse(title)) {
                sTabWebTitles.put(tab.getId(), title);
            }
        } catch (Exception e) {
            logi("rememberWebUrl", e.getMessage());
        }
    }

    /** 状态栏高度（窗口根 inset 的 top）；原生页面首帧就用它留出顶部空间，不用等自己的 inset 派发。 */
    public static int statusInsetPx() {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return 0;
        try {
            View content = activity.findViewById(android.R.id.content);
            WindowInsets insets = content != null ? content.getRootWindowInsets() : null;
            if (insets == null) return 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return insets.getInsets(WindowInsets.Type.statusBars()).top;
            }
            return insets.getSystemWindowInsetTop();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 「接管」：切到那个标签页。 */
    public static void switchToTab(Tab target) {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return;
        try {
            TabModelSelector selector = activity.getTabModelSelector();
            TabModel model = selector.getModel(target.isIncognito());
            int index = TabModelUtils.getTabIndexById(model, target.getId());
            if (index < 0) return;
            if (selector.getCurrentModel() != model) selector.selectModel(target.isIncognito());
            TabModelUtils.setIndex(model, index);
            if (sLayout != LayoutType.BROWSING) {
                activity.getLayoutManager().showLayout(LayoutType.BROWSING, true);
            }
            // 接管的是网页标签页：把它的对话盖上来。
            if (!MuseUrls.isMuse(target.getUrl().getSpec())) {
                showOverlay(target, MuseUrls.SECTION_CHAT);
            }
        } catch (Exception e) {
            logi("switchToTab", e.getMessage());
        }
    }

    /**
     * 导航栏当前占的高度（含手势条内边距）。收起时为 0。
     * 搜索打开期间仍返回收起前的高度，对话底部留白不变，不会先沉下去再进入搜索。
     */
    public static int navHeightPx() {
        LinearLayout nav = sNav;
        if (nav != null && nav.getVisibility() == View.VISIBLE) {
            int height = nav.getHeight();
            if (height <= 0) {
                try {
                    nav.measure(
                            View.MeasureSpec.makeMeasureSpec(
                                    ((View) nav.getParent()).getWidth(), View.MeasureSpec.AT_MOST),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    height = nav.getMeasuredHeight();
                } catch (Exception e) {
                    height = 0;
                }
            }
            if (height > 0) sNavHeight = height;
            return height > 0 ? height : sNavHeight;
        }
        if (sOmniboxOpen && sNavHeight > 0) return sNavHeight;
        return 0;
    }

    /** 地址栏已经接手时底栏让开。滑出过程中先留着，避免对话先塌一下。 */
    private static boolean omniboxHidesNav() {
        return sOmniboxOpen && !sSearchHandoff;
    }

    public static void addNavListener(Runnable listener) {
        if (!sNavListeners.contains(listener)) sNavListeners.add(listener);
    }

    public static void removeNavListener(Runnable listener) {
        sNavListeners.remove(listener);
    }

    private static void notifyNavListeners() {
        for (Runnable listener : new ArrayList<>(sNavListeners)) {
            try {
                listener.run();
            } catch (Exception e) {
                logi("nav listener", e.getMessage());
            }
        }
    }

    /** 原生页面请求暂时收起 / 放回导航栏（软键盘弹出期间）。 */
    public static void setNavSuppressed(Object who, boolean suppressed) {
        boolean changed = suppressed ? sNavSuppressors.add(who) : sNavSuppressors.remove(who);
        if (changed) ThreadUtils.runOnUiThread(LemurXConsoleShell::refresh);
    }

    /** 空地址栏盖在当前页上。不提交就收起，提交后由导航自己接手。 */
    public static void openSearch() {
        MusePage.dismissCovers();
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return;
        sSearchReturnUrl = null;
        sSearchWebUrl = null;
        sSearchReturnTabId = -1;
        navHeightPx();
        sSearchHandoff = true;
        sOmniboxOpen = true;
        // 搜索基于网页：网页上的覆盖层在地址栏打开期间自动让位，标签页本来就停在网页上，不用动。
        // 只有"纯原生页"标签页（新标签页的对话）且历史里有网页时才需要先回到网页再聚焦。
        try {
            Tab current = activity.areTabModelsInitialized() ? activity.getActivityTab() : null;
            if (current != null && MuseUrls.isMuse(current.getUrl().getSpec())) {
                String web = tabWebUrl(current);
                if (web != null) {
                    sSearchReturnUrl = current.getUrl().getSpec();
                    sSearchWebUrl = web;
                    sSearchReturnTabId = current.getId();
                    if (previousEntryIs(current, web)) {
                        current.goBack();
                    } else {
                        current.loadUrl(new LoadUrlParams(web));
                    }
                }
            }
        } catch (Exception e) {
            logi("openSearch", e.getMessage());
        }
        applyControls(true);
        if (sNav != null) {
            sNav.animate().cancel();
            sNav.animate().alpha(0f).setDuration(OMNIBOX_REVEAL_MS).setInterpolator(CHROME_EASE)
                    .start();
        }
        ThreadUtils.postOnUiThreadDelayed(
                () -> {
                    if (sActivity != activity || !sOmniboxOpen) return;
                    ToolbarManager toolbar = activity.getToolbarManager();
                    if (toolbar == null) {
                        sSearchHandoff = false;
                        if (sNav != null) {
                            sNav.animate().cancel();
                            sNav.setAlpha(1f);
                        }
                        sOmniboxOpen = false;
                        applyControls(true);
                        refresh();
                        return;
                    }
                    try {
                        AutocompleteInput input =
                                new AutocompleteInput(OmniboxFocusReason.MENU_OR_KEYBOARD_ACTION);
                        input.setUserText("");
                        toolbar.beginFuseboxInput(input);
                    } catch (Exception e) {
                        logi("openSearch", e.getMessage());
                        sOmniboxOpen = false;
                        applyControls(true);
                    }
                    sSearchHandoff = false;
                    refresh();
                },
                OMNIBOX_REVEAL_MS);
    }

    /** 打开标签页管理（Hub）。 */
    public static void openTabSwitcher() {
        rememberAttachedChat();
        freezeCard();
        MusePage.dismissCovers();
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return;
        try {
            activity.getLayoutManager().showLayout(LayoutType.HUB, true);
        } catch (Exception e) {
            logi("openTabSwitcher", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 导航栏视图

    /**
     * 挂在 android.R.id.content 最上层、贴底。只画导航栏自己，其余区域不接触摸。
     * 底部内边距跟随系统手势条；键盘弹出时 content 自己会缩，栏随之上移。
     */
    private static void mountNav(ChromeTabbedActivity activity) {
        FrameLayout content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        Resources res = activity.getResources();
        int padH = res.getDimensionPixelSize(R.dimen.lemurx_console_nav_padding_horizontal);
        int padTop = res.getDimensionPixelSize(R.dimen.lemurx_console_nav_padding_top);
        int padBottom = res.getDimensionPixelSize(R.dimen.lemurx_console_nav_padding_bottom);
        int maxWidth = res.getDimensionPixelSize(R.dimen.lemurx_console_pill_max_width);
        int pillPad = res.getDimensionPixelSize(R.dimen.lemurx_console_pill_padding);
        int cellHeight = res.getDimensionPixelSize(R.dimen.lemurx_console_cell_height);
        int iconSize = res.getDimensionPixelSize(R.dimen.lemurx_console_icon_size);
        int iconSmall = res.getDimensionPixelSize(R.dimen.lemurx_console_icon_size_small);

        // 药丸：网页钮、对话钮，然后是搜索、标签页、菜单。网页上网页钮收起，长出前进后退。
        // 宽度不超过 370dp，超出的屏幕居中。
        LinearLayout pill =
                new LinearLayout(activity) {
                    @Override
                    protected void onMeasure(int widthSpec, int heightSpec) {
                        int mode = View.MeasureSpec.getMode(widthSpec);
                        int size = View.MeasureSpec.getSize(widthSpec);
                        if (mode != View.MeasureSpec.UNSPECIFIED && size > maxWidth) {
                            widthSpec = View.MeasureSpec.makeMeasureSpec(maxWidth, mode);
                        }
                        super.onMeasure(widthSpec, heightSpec);
                    }
                };
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setPadding(pillPad, pillPad, pillPad, pillPad);
        pill.setBackgroundResource(R.drawable.lemurx_console_pill_bg);
        pill.setElevation(res.getDimensionPixelSize(R.dimen.lemurx_console_pill_elevation));
        pill.setClipToOutline(true);
        pill.setOutlineProvider(
                new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                                view.getHeight() / 2f);
                    }
                });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            int shadow = res.getColor(R.color.lemurx_console_shadow, activity.getTheme());
            // 网页：shadowColor #132631 opacity 0.07
            int soft = Color.argb(0x12, Color.red(shadow), Color.green(shadow), Color.blue(shadow));
            pill.setOutlineAmbientShadowColor(soft);
            pill.setOutlineSpotShadowColor(soft);
        }

        View backCell = cell(activity, R.drawable.lemurx_console_ic_back,
                R.string.lemurx_console_back, iconSize, cellHeight, v -> goHistoryBack());
        View forwardCell = cell(activity, R.drawable.lemurx_console_ic_forward,
                R.string.lemurx_console_forward, iconSize, cellHeight, v -> goHistoryForward());
        applySlotWeight(backCell, 0f);
        applySlotWeight(forwardCell, 0f);
        pill.addView(backCell);
        pill.addView(forwardCell);
        sBackCell = backCell;
        sForwardCell = forwardCell;
        View[] sectionCells = new View[3];
        sectionCells[CELL_WEB] = cell(activity, R.drawable.lemurx_console_ic_web,
                R.string.lemurx_console_web, iconSize, cellHeight, v -> goWeb());
        sectionCells[CELL_CHAT] = cell(activity, R.drawable.lemurx_console_ic_chat,
                R.string.lemurx_console_chat, iconSize, cellHeight, v -> goChat());
        sSectionCells = sectionCells;
        View tabs = tabsCell(activity, iconSmall, cellHeight);
        sTabsCell = tabs;
        pill.addView(sectionCells[CELL_WEB]);
        pill.addView(sectionCells[CELL_CHAT]);
        // 装了插件才占一格，夹在对话和搜索中间。点一下才把上面的插件栏展开。
        View extensions = cell(activity, R.drawable.lemurx_console_ic_extensions,
                R.string.lemurx_console_extensions, iconSize, cellHeight, v -> toggleExtensionsBar());
        sExtensionsCell = extensions;
        applySlotWeight(extensions, 0f);
        pill.addView(extensions);
        // 进出网页时网页钮和前进后退对调；搜索、标签页、菜单只跟着间距动。
        pill.addView(cell(activity, R.drawable.lemurx_console_ic_search,
                R.string.lemurx_console_search, iconSmall, cellHeight, v -> openSearch()));
        pill.addView(tabs);
        View menu = cell(activity, R.drawable.lemurx_muse_ic_menu,
                R.string.lemurx_console_menu, iconSize, cellHeight, v -> openSidebar());
        sMenuCell = menu;
        pill.addView(menu);

        // 整条贴底的容器是全宽的，药丸居中。旁边空白（对话发送钮就在右下）必须把触摸漏下去。
        LinearLayout nav =
                new LinearLayout(activity) {
                    @Override
                    public boolean dispatchTouchEvent(MotionEvent ev) {
                        if (!touchHitsVisibleChild(this, ev)) return false;
                        return super.dispatchTouchEvent(ev);
                    }

                    @Override
                    public boolean onTouchEvent(MotionEvent ev) {
                        return false;
                    }
                };
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setGravity(Gravity.CENTER_HORIZONTAL);
        nav.setPadding(padH, padTop, padH, padBottom);
        nav.setClipChildren(false);
        nav.setClipToPadding(false);
        nav.addView(pill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 已固定的插件排在药丸上面，只在看网页时出现。
        LemurXPinnedBar.attach(activity, nav);
        nav.setVisibility(View.GONE);
        nav.setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    // Chrome 自己的根布局会把系统栏 inset 消费掉再往下传，派发到这里的
                    // 往往已经是 0；要用窗口原始的那份，栏才能停在手势条上方。
                    WindowInsets root = v.getRootWindowInsets();
                    v.setPadding(padH, padTop, padH,
                            padBottom + bottomInset(root != null ? root : insets));
                    return insets;
                });

        // Agent 覆盖层的容器：铺满内容区，压在网页上、导航栏下。
        FrameLayout overlayHost = new FrameLayout(activity);
        overlayHost.setVisibility(View.GONE);
        // 底色铺满，键盘把对话垫高时底下不会露出网页。
        overlayHost.setBackgroundColor(MusePage.bg(activity));
        // Chrome 的根布局是 edge-to-edge 的，软键盘弹出时不会把 android.R.id.content 挤矮，
        // 网页那边是 Chrome 自己调视口。覆盖层要自己按键盘高度留出底部内边距，输入栏才会
        // 贴着键盘；有键盘动画时逐帧跟着走，落在键盘上而不是先跳到终点再等键盘。
        overlayHost.setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    if (!sOverlayImeAnimating) applyOverlayIme(v, null);
                    return insets;
                });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            overlayHost.setWindowInsetsAnimationCallback(
                    new WindowInsetsAnimation.Callback(
                            WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                        @Override
                        public void onPrepare(WindowInsetsAnimation animation) {
                            if ((animation.getTypeMask() & WindowInsets.Type.ime()) != 0) {
                                sOverlayImeAnimating = true;
                                if (overlayHost.getVisibility() == View.VISIBLE) {
                                    concealCompositorForIme(true);
                                }
                            }
                        }

                        @Override
                        public WindowInsets onProgress(
                                WindowInsets insets,
                                java.util.List<WindowInsetsAnimation> running) {
                            if (sOverlayImeAnimating) applyOverlayIme(overlayHost, insets);
                            return insets;
                        }

                        @Override
                        public void onEnd(WindowInsetsAnimation animation) {
                            if ((animation.getTypeMask() & WindowInsets.Type.ime()) != 0) {
                                sOverlayImeAnimating = false;
                                applyOverlayIme(overlayHost, null);
                            }
                        }
                    });
        }
        content.addView(
                overlayHost,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sOverlayHost = overlayHost;

        FrameLayout.LayoutParams lp =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        content.addView(nav, lp);
        sNav = nav;
        // 底栏是内容区里最后决定叠放的一层。后面再挂进来的网页壳、覆盖层，都把它请回最上面。
        float navZ = 32f * res.getDisplayMetrics().density;
        nav.setTranslationZ(navZ);
        content.setOnHierarchyChangeListener(
                new ViewGroup.OnHierarchyChangeListener() {
                    @Override
                    public void onChildViewAdded(View parent, View child) {
                        LinearLayout bar = sNav;
                        if (bar == null || child == bar) return;
                        // Agent 操作时锁在底栏上面，子视图增减不能把底栏抬回来。
                        if (LemurXDeviceBridge.isDriving()) {
                            LemurXDeviceBridge.keepLockOnTop();
                            return;
                        }
                        if (bar.getVisibility() != View.VISIBLE) return;
                        bar.bringToFront();
                    }

                    @Override
                    public void onChildViewRemoved(View parent, View child) {}
                });

        // 顶部进度条：贴在状态栏下沿，宽度按进度 scaleX。
        View progress = new View(activity);
        progress.setBackgroundColor(res.getColor(R.color.lemurx_console_icon, activity.getTheme()));
        progress.setPivotX(0f);
        progress.setScaleX(0f);
        progress.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Math.round(PROGRESS_HEIGHT_DP * res.getDisplayMetrics().density));
        plp.gravity = Gravity.TOP;
        content.addView(progress, plp);
        sProgress = progress;

        sNavPadH = padH;
        sNavPadTop = padTop;
        sNavPadBottom = padBottom;

        nav.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> {
                    if ((b - t) != (ob - ot)) notifyNavListeners();
                });
        // 有些窗口不会再派发一次 insets；主动从根窗口拿一次。
        WindowInsets rootInsets = content.getRootWindowInsets();
        if (rootInsets != null) {
            nav.setPadding(padH, padTop, padH, padBottom + bottomInset(rootInsets));
        }
        nav.requestApplyInsets();
    }

    /**
     * 盯着前台网页的滚动。往下滚过 {@code NAV_HIDE_DP} 收起底栏，往上滚过 {@code NAV_SHOW_DP}
     * 或回到页顶就出现。要订阅根滚动偏移的每次变化（ALL_UPDATES），否则回调里的 scrollOffsetY
     * 只是缓存值。
     */
    private static void watchGesture(@Nullable Tab tab) {
        WebContents contents = null;
        try {
            if (tab != null && !tab.isNativePage()) contents = tab.getWebContents();
        } catch (Exception e) {
            contents = null;
        }
        if (contents == sGestureContents) return;
        detachGesture();
        if (contents == null) return;
        try {
            final float density =
                    ContextUtils.getApplicationContext().getResources().getDisplayMetrics().density;
            final int hidePx = Math.round(NAV_HIDE_DP * density);
            final int showPx = Math.round(NAV_SHOW_DP * density);
            final int topPx = Math.round(NAV_TOP_DP * density);
            GestureStateListener listener =
                    new GestureStateListener() {
                        // 按根滚动偏移本身判断，而不是按 isDirectionUp 之类的方向标志：
                        // 往下滚过 NAV_HIDE_DP 就收起，往上滚过 NAV_SHOW_DP 就出现（再慢也算），
                        // 回到页顶一定出现。方向反转时累计量清零，这样来回抖一两个像素不会闪。
                        //
                        // 只认手势期间的偏移变化：会话恢复 / 锚点跳转等程序化滚动也会改偏移，
                        // 不能让它们把刚打开的导航栏收掉。
                        //
                        // onFlingStartGesture / onVerticalScrollDirectionChanged 故意不用：native 对
                        // fling 事件也读 scroll_begin.delta_y_hint，但那个字段在 union 里和
                        // fling_start 错位，拿到的方向是垃圾值。
                        private boolean mGesture;
                        private int mLastY = Integer.MIN_VALUE;
                        private int mAccum;

                        @Override
                        public void onScrollStarted(
                                int scrollOffsetY, int scrollExtentY, boolean isDirectionUp) {
                            mGesture = true;
                            mAccum = 0;
                            mLastY = scrollOffsetY;
                        }

                        @Override
                        public void onScrollOffsetOrExtentChanged(
                                int scrollOffsetY, int scrollExtentY) {
                            int last = mLastY;
                            mLastY = scrollOffsetY;
                            if (!mGesture || last == Integer.MIN_VALUE) return;
                            int delta = scrollOffsetY - last;
                            if (delta == 0) return;
                            if ((delta > 0) != (mAccum > 0)) mAccum = 0;
                            mAccum += delta;
                            if (scrollOffsetY <= topPx) {
                                setWebCollapsed(false);
                            } else if (mAccum >= hidePx) {
                                setWebCollapsed(true);
                            } else if (mAccum <= -showPx) {
                                setWebCollapsed(false);
                            }
                        }

                        @Override
                        public void onScrollEnded(int scrollOffsetY, int scrollExtentY) {
                            onScrollStopped();
                        }

                        @Override
                        public void onFlingEndGesture(int scrollOffsetY, int scrollExtentY) {
                            onScrollStopped();
                        }

                        private void onScrollStopped() {
                            mGesture = false;
                            mAccum = 0;
                        }
                    };
            GestureListenerManager.fromWebContents(contents)
                    .addListener(listener, RootScrollOffsetUpdateFrequency.ALL_UPDATES);
            sGestureContents = contents;
            sGestureListener = listener;
        } catch (Exception e) {
            logi("watchGesture", e.getMessage());
        }
    }

    private static void setWebCollapsed(boolean collapsed) {
        if (sWebCollapsed == collapsed) return;
        sWebCollapsed = collapsed;
        refresh();
    }

    private static void detachGesture() {
        if (sGestureContents != null && sGestureListener != null) {
            try {
                if (!sGestureContents.isDestroyed()) {
                    GestureListenerManager.fromWebContents(sGestureContents)
                            .removeListener(sGestureListener);
                }
            } catch (Exception e) {
                // WebContents 可能已经销毁
            }
        }
        sGestureContents = null;
        sGestureListener = null;
    }

    private static int bottomInset(WindowInsets insets) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return insets.getInsets(WindowInsets.Type.systemBars()).bottom;
            }
            return insets.getSystemWindowInsetBottom();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 触摸是否落在可见子视图上（含再下一层）。药丸外面的空白内边距要漏给底下的发送钮。
     * 栏里每一格已经按可见图标平分了宽度，点在这一格的任意位置都算点中，不要求正好点在图标上。
     */
    private static boolean touchHitsVisibleChild(ViewGroup parent, MotionEvent ev) {
        float x = ev.getX() + parent.getScrollX();
        float y = ev.getY() + parent.getScrollY();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (x < child.getLeft() || x >= child.getRight() || y < child.getTop()
                    || y >= child.getBottom()) {
                continue;
            }
            if (child.isClickable()) return true;
            if (child instanceof ViewGroup) {
                MotionEvent shifted = MotionEvent.obtain(ev);
                shifted.offsetLocation(-child.getLeft(), -child.getTop());
                boolean hit = touchHitsVisibleChild((ViewGroup) child, shifted);
                shifted.recycle();
                if (hit) return true;
                if (((ViewGroup) child).getChildCount() == 0) return true;
                continue;
            }
            return true;
        }
        return false;
    }

    private static View cell(
            Context context,
            @DrawableRes int icon,
            @StringRes int desc,
            int iconSize,
            int cellHeight,
            View.OnClickListener onClick) {
        FrameLayout cell = new FrameLayout(context);
        cell.setBackgroundResource(R.drawable.lemurx_console_cell_bg);
        cell.setContentDescription(context.getString(desc));
        cell.setOnClickListener(
                v -> {
                    if (LemurXDeviceBridge.isDriving()) return;
                    LemurXHaptics.tick(v);
                    MusePage.closeDrawer();
                    onClick.onClick(v);
                });
        cell.setClickable(true);
        cell.setFocusable(true);
        ImageView image = new ImageView(context);
        image.setImageResource(icon);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(iconSize, iconSize);
        ilp.gravity = Gravity.CENTER;
        cell.addView(image, ilp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, cellHeight, 1f);
        cell.setLayoutParams(lp);
        return cell;
    }

    /** 标签页按钮。白底盖住图标右下角那一格，数字标当前窗口还开着几个标签。 */
    private static View tabsCell(Context context, int iconSize, int cellHeight) {
        FrameLayout cell = new FrameLayout(context);
        cell.setBackgroundResource(R.drawable.lemurx_console_cell_bg);
        cell.setContentDescription(context.getString(R.string.lemurx_console_tabs));
        cell.setOnClickListener(
                v -> {
                    if (LemurXDeviceBridge.isDriving()) return;
                    LemurXHaptics.tick(v);
                    MusePage.closeDrawer();
                    openTabSwitcher();
                });
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setClipChildren(false);
        FrameLayout icon = new FrameLayout(context);
        icon.setClipChildren(false);
        ImageView image = new ImageView(context);
        image.setImageResource(R.drawable.lemurx_console_ic_tabs);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.addView(
                image,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 右下角那一格大约 11dp。底板收到 11dp，盖住这一格，一位数最高 10sp。
        float density = context.getResources().getDisplayMetrics().density;
        int badgeSize = Math.round(11f * density);
        TextView count = new TextView(context);
        count.setTextColor(context.getColor(R.color.lemurx_console_tab_count));
        count.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        count.setGravity(Gravity.CENTER);
        count.setIncludeFontPadding(false);
        count.setPadding(0, 0, 0, 0);
        count.setMaxLines(1);
        count.setAutoSizeTextTypeUniformWithConfiguration(
                8, 10, 1, TypedValue.COMPLEX_UNIT_SP);
        GradientDrawable plate = new GradientDrawable();
        plate.setColor(context.getColor(R.color.lemurx_console_tab_count_bg));
        plate.setCornerRadius(3f * density);
        count.setBackground(plate);
        FrameLayout.LayoutParams countLp = new FrameLayout.LayoutParams(badgeSize, badgeSize);
        countLp.gravity = Gravity.BOTTOM | Gravity.END;
        icon.addView(count, countLp);
        sTabCount = count;
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(iconSize, iconSize);
        iconLp.gravity = Gravity.CENTER;
        cell.addView(icon, iconLp);
        cell.setLayoutParams(new LinearLayout.LayoutParams(0, cellHeight, 1f));
        return cell;
    }

    /** 右下角数字跟当前窗口的标签数走。普通和无痕各算各的。 */
    private static void updateTabCount() {
        TextView count = sTabCount;
        ChromeTabbedActivity activity = sActivity;
        if (count == null || activity == null) return;
        int n = 0;
        try {
            if (activity.areTabModelsInitialized()) {
                TabModel model = activity.getTabModelSelector().getCurrentModel();
                if (model != null) n = model.getCount();
            }
        } catch (RuntimeException e) {
            return;
        }
        String text = n > 99 ? "99+" : Integer.toString(n);
        if (!text.contentEquals(count.getText())) count.setText(text);
        View parent = (View) count.getParent();
        if (parent != null && parent.getParent() instanceof View) {
            ((View) parent.getParent())
                    .setContentDescription(
                            activity.getString(R.string.lemurx_console_tabs_count, n));
        }
    }

    /**
     * 按当前状态决定导航栏显不显示，并点亮当前分区：外壳启用、在浏览布局、地址栏没打开、
     * 没有原生页面要求收起（软键盘）、前台有标签，且不是"没带外壳标记的旧网页首页"
     * （那种首页由网页自己画栏）。浏览网页时底栏保持在最上面，不随滚动收起。
     */
    /** 独立层把自己抬到最前之后，底栏仍要留在上面，点得到。Agent 锁着的时候不抬。 */
    public static void liftNav() {
        if (LemurXDeviceBridge.isDriving()) {
            LemurXDeviceBridge.keepLockOnTop();
            return;
        }
        bringNavToFront();
    }

    /** 已经在最前就不动。bringToFront 会让整个窗口重新布局，切换页面时反复调用就会卡。 */
    private static void bringNavToFront() {
        View nav = sNav;
        if (nav == null || nav.getVisibility() != View.VISIBLE) return;
        ViewParent parent = nav.getParent();
        if (!(parent instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) parent;
        if (group.getChildAt(group.getChildCount() - 1) == nav) return;
        nav.bringToFront();
    }

    /** 这次触摸是不是落在底栏范围内。独立层把这一条让出去，导航栏才能接到。 */
    public static boolean touchInNav(View layer, MotionEvent ev) {
        View nav = sNav;
        if (nav == null || nav.getVisibility() != View.VISIBLE || nav.getHeight() == 0) return false;
        int[] navLoc = new int[2];
        int[] layerLoc = new int[2];
        nav.getLocationInWindow(navLoc);
        layer.getLocationInWindow(layerLoc);
        float x = ev.getX() + layerLoc[0];
        float y = ev.getY() + layerLoc[1];
        return x >= navLoc[0] && x < navLoc[0] + nav.getWidth()
                && y >= navLoc[1] && y < navLoc[1] + nav.getHeight();
    }

    /** 底栏当前占掉的高度（像素）；隐藏时为 0。agent 键盘等要贴底的视图用它避让。 */
    public static int bottomBarHeight() {
        View nav = sNav;
        if (nav == null || nav.getVisibility() != View.VISIBLE) return 0;
        return nav.getHeight();
    }

    private static void refresh() {
        ChromeTabbedActivity activity = sActivity;
        LinearLayout nav = sNav;
        if (activity == null || nav == null) return;
        boolean show = false;
        int active = -1;
        boolean hasWeb = true;
        Tab tab = null;
        // 网页上的 Agent 覆盖层：浏览布局、地址栏没开、前台是网页且这个标签页开着覆盖层。
        // 它不受软键盘压栏的影响（打字时栏收起、覆盖层当然还在）。
        Tab overlayTab = null;
        String overlaySection = null;
        try {
            if (isEnabled() && sLayout == LayoutType.BROWSING && !omniboxHidesNav()) {
                Tab fg = activity.getActivityTab();
                if (fg != null && !MuseUrls.isMuse(fg.getUrl().getSpec())) {
                    overlaySection = sOverlaySection.get(fg.getId());
                    if (overlaySection != null) overlayTab = fg;
                }
            }
            if (isEnabled() && sLayout == LayoutType.BROWSING && !omniboxHidesNav()
                    && sNavSuppressors.isEmpty()) {
                tab = activity.getActivityTab();
                if (tab != null) {
                    String url = tab.getUrl().getSpec();
                    show = !isHome(url) || isShellHome(url);
                    active = activeIndex(url);
                    // 恢复会话回来的标签页没经过导航回调，这里顺手把正在看的网页记下。
                    if (active == CELL_WEB) rememberWebUrl(tab, tab.getUrl());
                    hasWeb = active == CELL_WEB || tabWebUrl(tab) != null;
                    // 侧边栏打开的文件架和电脑是盖层，不改底栏选中项；底栏在那两层打开时收起。
                    if (MusePage.communityOpen()) {
                        active = CELL_COMMUNITY;
                    } else if (overlayTab == tab && overlaySection != null) {
                        active = MuseUrls.SECTION_COMMUNITY.equals(overlaySection)
                                ? CELL_COMMUNITY
                                : CELL_CHAT;
                    }
                    sPlace = active;
                }
            }
        } catch (Exception e) {
            logi("refresh", e.getMessage());
        }
        syncOverlay(overlayTab);
        // 网页模式：刚从别的标签页 / 原生页面进来时先显示；之后跟着这页的滚动方向走。
        boolean webMode = show && sPlace == CELL_WEB && tab != null;
        if (webMode) {
            if (sWebModeTabId != tab.getId()) sWebCollapsed = false;
            sWebModeTabId = tab.getId();
        } else {
            sWebModeTabId = -1;
        }
        watchGesture(webMode ? tab : null);
        syncProgress(activity);
        boolean collapsed = webMode && sWebCollapsed;
        sWebReady = hasWeb;
        View[] cells = sSectionCells;
        if (cells != null) {
            // 这个标签页还没看过网页：网页格压暗（点了会唤起地址栏）。网页钮的透明度在排按钮时一起算。
            for (int i = 0; i < cells.length; i++) {
                if (cells[i] == null) continue;
                // 正在看网页时地球格会收起，底栏不保持一个默认选中项。
                boolean selected = i == active && !webMode;
                if (cells[i].isSelected() != selected || cells[i].getTag() == null) {
                    cells[i].setSelected(selected);
                    cells[i].setBackgroundResource(selected
                            ? R.drawable.lemurx_console_cell_active_bg
                            : R.drawable.lemurx_console_cell_bg);
                    cells[i].setTag(Boolean.TRUE);
                }
            }
        }
        updateExtensionsSlot(activity);
        morphWebChrome(webMode);
        updateHistoryButtons(webMode ? tab : null);
        int vis = show && !collapsed && !sPipChrome ? View.VISIBLE : View.GONE;
        if (vis == View.VISIBLE && !sSearchHandoff) {
            nav.animate().cancel();
            nav.setAlpha(1f);
        }
        if (nav.getVisibility() != vis) {
            if (vis == View.VISIBLE) syncInsets(nav, sNavPadH, sNavPadTop, sNavPadBottom);
            nav.setVisibility(vis);
            LemurXDeviceBridge.keepLockOnTop();
            notifyNavListeners();
        }
        // 每次刷新都抬到最上面。网页合成层和后来加上的覆盖层不能盖住底栏。
        // Agent 正在操作时反过来：锁盖住底栏，接管之前底栏不能点。
        if (vis == View.VISIBLE && !LemurXDeviceBridge.isDriving()) bringNavToFront();
        LemurXPinnedBar.sync(
                activity, webMode && sExtensionsBarOpen && sHasExtensions && vis == View.VISIBLE);
        LemurXDeviceBridge.keepLockOnTop();
        updateTabCount();
        if (!sOmniboxOpen && tabsReady()) {
            try {
                Tab fg = activity.getActivityTab();
                if (fg != null) rememberForeground(fg, sOverlaySection.get(fg.getId()));
            } catch (Exception e) {
                logi("rememberForeground", e.getMessage());
            }
        }
    }

    /**
     * 网页模式：前进后退从左侧长出来，网页钮收起。Agent 页上对话钮本来就排在网页钮后面，
     * 网页钮让位后它留在前进后退右侧。搜索、标签页、菜单不换顺序，只跟着可用宽度改间距。离开网页时反过来。侧边栏三条线两种模式都留在最右边。
     * weight 0↔1 + 透明度 + 轻微缩放，280ms FastOutSlowIn。每一帧只摆药丸里的按钮，
     * 不触发整窗布局；同一次切换里后来的刷新也不把动画时钟清零。
     */
    private static void morphWebChrome(boolean webChrome) {
        View back = sBackCell;
        View forward = sForwardCell;
        View[] cells = sSectionCells;
        if (back == null || forward == null || cells == null) return;
        if (sChromeAnim != null && sChromeAnim.isRunning() && sWebChrome == webChrome) return;
        float target = webChrome ? 1f : 0f;
        float current = slotWeight(back);
        if (sWebChrome == webChrome && Math.abs(current - target) < 0.01f) {
            applyChrome(webChrome ? 1f : 0f);
            return;
        }
        prepareChatTrack(webChrome);
        sWebChrome = webChrome;
        if (sChromeAnim != null) sChromeAnim.cancel();
        if (Math.abs(current - target) < 0.01f) {
            applyChrome(target);
            return;
        }
        ValueAnimator anim = ValueAnimator.ofFloat(current, target);
        anim.setDuration(CHROME_MORPH_MS);
        anim.setInterpolator(CHROME_EASE);
        anim.addUpdateListener(a -> applyChrome((float) a.getAnimatedValue()));
        sChromeAnim = anim;
        anim.start();
    }

    /** 点插件钮：展开或收起底栏上面那一排已固定的插件。 */
    private static void toggleExtensionsBar() {
        if (!sHasExtensions) return;
        sExtensionsBarOpen = !sExtensionsBarOpen;
        refresh();
    }

    /** 没装插件时这一格权重为 0，不占位置。展开时用导航选中格的底。 */
    private static void updateExtensionsSlot(ChromeTabbedActivity activity) {
        boolean installed = false;
        try {
            Tab tab = activity.getActivityTab();
            Profile profile =
                    tab != null ? tab.getProfile() : ProfileManager.getLastUsedRegularProfile();
            if (profile != null) installed = LemurXExtensions.hasInstalled(profile);
        } catch (RuntimeException e) {
            installed = sHasExtensions;
        }
        if (!installed) sExtensionsBarOpen = false;
        sHasExtensions = installed;
        View cell = sExtensionsCell;
        if (cell == null) return;
        boolean selected = sExtensionsBarOpen && installed;
        if (cell.isSelected() != selected || cell.getTag() == null) {
            cell.setSelected(selected);
            cell.setBackgroundResource(
                    selected
                            ? R.drawable.lemurx_console_cell_active_bg
                            : R.drawable.lemurx_console_cell_bg);
            cell.setTag(Boolean.TRUE);
        }
    }

    private static void applyChrome(float chrome) {
        applySlotWeight(sBackCell, chrome);
        applySlotWeight(sForwardCell, chrome);
        View[] cells = sSectionCells;
        // 搜索、标签、菜单两种模式都在，顺序不动，宽度跟着前后钮的权重一起变。
        applySlotWeight(sTabsCell, 1f);
        applySlotWeight(sMenuCell, 1f);
        // 插件钮只在看网页时占一格，对话界面权重收到 0。
        applySlotWeight(sExtensionsCell, sHasExtensions ? chrome : 0f);
        if (cells != null) {
            float chat = sPlace == CELL_CHAT ? 1f : (sChatTracksChrome ? chrome : 1f);
            applySlotWeight(cells[CELL_WEB], 1f - chrome);
            applySlotWeight(cells[CELL_CHAT], chat);
            if (!sWebReady) cells[CELL_WEB].setAlpha(0.4f * (1f - chrome));
        }
        placeSlots();
        paintHistoryAlpha();
    }

    /**
     * 对话钮已经单独露在底栏上时，进出网页保持占位，只让网页钮和前进后退做动画。
     */
    private static void prepareChatTrack(boolean webChrome) {
        View[] cells = sSectionCells;
        View chat = cells == null ? null : cells[CELL_CHAT];
        if (webChrome) {
            sChatTracksChrome = slotWeight(chat) < 0.99f;
        } else {
            sChatTracksChrome = false;
        }
    }

    private static float slotWeight(@Nullable View view) {
        if (view == null || !(view.getLayoutParams() instanceof LinearLayout.LayoutParams)) {
            return 0f;
        }
        return ((LinearLayout.LayoutParams) view.getLayoutParams()).weight;
    }

    private static void applySlotWeight(@Nullable View view, float weight) {
        if (view == null) return;
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (!(raw instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
        // 只改权重，不走 setLayoutParams。那会把布局请求冒到整个窗口，切换时每一帧都卡一下。
        lp.width = 0;
        lp.weight = weight;
        view.setAlpha(weight);
        float scale = 0.72f + 0.28f * weight;
        view.setScaleX(scale);
        view.setScaleY(scale);
        boolean on = weight > 0.08f;
        if (view.isClickable() != on) {
            view.setClickable(on);
            view.setFocusable(on);
            view.setImportantForAccessibility(
                    on ? View.IMPORTANT_FOR_ACCESSIBILITY_YES
                            : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }

    /** 按当前权重把药丸里的按钮摆好。药丸自己的宽高不变，所以不用重新测量窗口。 */
    private static void placeSlots() {
        View anchor = sBackCell;
        if (anchor == null || !(anchor.getParent() instanceof ViewGroup)) return;
        ViewGroup pill = (ViewGroup) anchor.getParent();
        int inner = pill.getWidth() - pill.getPaddingLeft() - pill.getPaddingRight();
        if (inner <= 0) return;
        int count = pill.getChildCount();
        float sum = 0f;
        for (int i = 0; i < count; i++) sum += slotWeight(pill.getChildAt(i));
        if (sum <= 0f) return;
        int x = pill.getPaddingLeft();
        int top = pill.getPaddingTop();
        int used = 0;
        for (int i = 0; i < count; i++) {
            View child = pill.getChildAt(i);
            int width = i == count - 1
                    ? Math.max(0, inner - used)
                    : Math.round(inner * slotWeight(child) / sum);
            used += width;
            int height = child.getLayoutParams().height;
            if (height <= 0) height = child.getHeight();
            child.measure(
                    View.MeasureSpec.makeMeasureSpec(Math.max(width, 0), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(Math.max(height, 0), View.MeasureSpec.EXACTLY));
            child.setPivotX(width / 2f);
            child.setPivotY(height / 2f);
            child.layout(x, top, x + width, top + height);
            x += width;
        }
    }

    /** 盖层打开或收起之后重算底栏。 */
    public static void onPlaceChanged() {
        refresh();
        liftNav();
        // 社区、档案这些盖层不经过内置返回处理器，盖上或揭开时要自己刷新返回键是否该由我们接住。
        BackPressManager.lemurXConsoleStateChanged();
    }

    /** 网页模式下按历史点亮 / 压暗前进后退。 */
    private static void updateHistoryButtons(@Nullable Tab tab) {
        View back = sBackCell;
        View forward = sForwardCell;
        if (back == null || forward == null || !sWebChrome) return;
        boolean canBack = false;
        boolean canForward = false;
        try {
            if (tab != null) {
                canBack = tab.canGoBack();
                canForward = tab.canGoForward();
            }
        } catch (Exception e) {
            canBack = false;
            canForward = false;
        }
        sHistBack = canBack;
        sHistForward = canForward;
        back.setEnabled(canBack);
        forward.setEnabled(canForward);
        paintHistoryAlpha();
    }

    /** 前进后退的明暗跟着能点与否，也跟着它正在展开的宽度。 */
    private static void paintHistoryAlpha() {
        View back = sBackCell;
        View forward = sForwardCell;
        if (back != null) back.setAlpha((sHistBack ? 1f : 0.32f) * slotWeight(back));
        if (forward != null) forward.setAlpha((sHistForward ? 1f : 0.32f) * slotWeight(forward));
    }

    // ---------------------------------------------------------------- 网页上的 Agent 覆盖层

    /** 在这个网页标签页上盖上对话 / 社区。会话 id 没有就新开一轮并记到标签页上。 */
    private static void showOverlay(Tab tab, String section) {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return;
        String url;
        if (MuseUrls.SECTION_COMMUNITY.equals(section)) {
            url = MuseUrls.COMMUNITY_URL;
        } else {
            String thread = tabThread(tab);
            if (thread == null) {
                thread = MuseUrls.threadOf(MuseUrls.newChatUrl());
                setTabThread(tab, thread);
            }
            url = MuseUrls.chatUrl(thread);
        }
        try {
            MusePage page = sOverlays.get(tab.getId());
            if (page == null) {
                page = MusePage.overlay(tab, activity, LemurXConsoleShell::openInNewTab, url);
                sOverlays.put(tab.getId(), page);
            } else {
                page.updateForUrl(url);
            }
        } catch (Exception e) {
            logi("showOverlay", e.getMessage());
            return;
        }
        sOverlaySection.put(tab.getId(), section);
        refresh();
    }

    /** 当前这个浏览器窗口。登录底栏和画中画都从这里拿 Activity。 */
    public static @Nullable Activity activity() {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        return activity;
    }

    /**
     * 画中画：有网页就把整页缩小放进小窗；没有网页就换成一块没有按钮的静画面。
     * 导航栏、对话层和进度条都不进小窗。
     */
    public static void setPipChrome(boolean pip) {
        ThreadUtils.assertOnUiThread();
        sPipChrome = pip;
        if (!pip) {
            hidePipCard();
            releasePagePin();
        }
        if (pip && foregroundHasWeb()) revealWeb();
        refresh();
        if (pip && !foregroundHasWeb()) showPipCard();
        else hidePipCard();
        if (pip) {
            // 键盘盖住的是缩短后的视口。先收起，再按整页钉住。
            hidePipIme();
            pinPageForPip();
        }
    }

    /**
     * 进画中画之前就能用的网页宽高比。系统要求大约在 1/2.39 和 2.39 之间，超出就收到边界。
     */
    public static @Nullable Rational pageAspectRatio() {
        int[] size = pagePixelSize();
        int w = size[0];
        int h = size[1];
        if (w <= 0 || h <= 0) return null;
        double ratio = (double) w / (double) h;
        final double min = 1.0 / 2.39;
        final double max = 2.39;
        if (ratio < min) w = Math.max(1, (int) Math.round(h * min));
        else if (ratio > max) h = Math.max(1, (int) Math.round(w / max));
        return new Rational(w, h);
    }

    /** 网页在窗口里的矩形，给进入画中画的缩放动画用。 */
    public static @Nullable Rect pageRectInWindow() {
        View page = compositor();
        if (page == null || page.getWidth() <= 0 || page.getHeight() <= 0) return null;
        int[] loc = new int[2];
        page.getLocationInWindow(loc);
        Rect rect = new Rect(loc[0], loc[1], loc[0] + page.getWidth(), loc[1] + page.getHeight());
        View root = page.getRootView();
        if (root != null) {
            if (rect.left < 0) rect.left = 0;
            if (rect.top < 0) rect.top = 0;
            if (rect.right > root.getWidth()) rect.right = root.getWidth();
            if (rect.bottom > root.getHeight()) rect.bottom = root.getHeight();
        }
        if (rect.width() <= 0 || rect.height() <= 0) return null;
        return rect;
    }

    /** 记下当前整页像素尺寸，并把合成器钉在这个尺寸上，避免小窗把网页重排成窄栏。 */
    private static void pinPageForPip() {
        View page = compositor();
        if (page == null) return;
        int w = page.getWidth();
        int h = page.getHeight();
        // 已经在小窗里时不要用缩小后的尺寸覆盖进窗前记下的整页。
        if ((sPipPageWidth <= 0 || sPipPageHeight <= 0) && w >= 600 && h >= 600) {
            sPipPageWidth = w;
            sPipPageHeight = h;
            captureWebContentsSize();
        }
        if (sPipPageWidth <= 0 || sPipPageHeight <= 0) return;
        ViewGroup.LayoutParams lp = page.getLayoutParams();
        if (lp != null && (lp.width != sPipPageWidth || lp.height != sPipPageHeight)) {
            lp.width = sPipPageWidth;
            lp.height = sPipPageHeight;
            page.setLayoutParams(lp);
        }
        page.setPivotX(0f);
        page.setPivotY(0f);
        unclip(page);
        watchPipParent(page);
        watchPipIme();
        fitPageScale(page);
        keepWebContentsFullSize();
        // 键盘收起是异步的。这一帧视口可能还是短的，下一帧变高就改记整页。
        page.post(
                () -> {
                    if (!sPipChrome) return;
                    adoptTallerWebContents();
                    keepWebContentsFullSize();
                });
    }

    /** 退出画中画：网页回到铺满窗口，缩放还原。 */
    private static void releasePagePin() {
        if (sPipScaleListener != null && sPipScaleParent != null) {
            sPipScaleParent.removeOnLayoutChangeListener(sPipScaleListener);
        }
        sPipScaleParent = null;
        View page = compositor();
        sPipPageWidth = 0;
        sPipPageHeight = 0;
        sPipWebWidth = 0;
        sPipWebHeight = 0;
        sPipImeSeen = -1;
        sPipFitPosted = false;
        sPipLoggedScale = 0f;
        unwatchPipIme();
        restoreClip();
        if (page == null) return;
        page.setScaleX(1f);
        page.setScaleY(1f);
        page.setTranslationX(0f);
        page.setTranslationY(0f);
        ViewGroup.LayoutParams lp = page.getLayoutParams();
        if (lp != null
                && (lp.width != ViewGroup.LayoutParams.MATCH_PARENT
                        || lp.height != ViewGroup.LayoutParams.MATCH_PARENT)) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            page.setLayoutParams(lp);
        }
    }

    private static void watchPipParent(View page) {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return;
        View parent = activity.findViewById(android.R.id.content);
        if (parent == null && page.getParent() instanceof View) parent = (View) page.getParent();
        if (parent == null) return;
        if (sPipScaleListener == null) {
            sPipScaleListener =
                    (v, l, t, r, b, ol, ot, or, ob) -> {
                        View pinned = compositor();
                        if (pinned == null || !sPipChrome || sPipFitPosted) return;
                        sPipFitPosted = true;
                        // 布局过程中改尺寸会再触发布局，放到下一帧，避免重入。
                        pinned.post(
                                () -> {
                                    sPipFitPosted = false;
                                    if (sPipChrome) pinPageForPip();
                                });
                    };
        }
        if (sPipScaleParent == parent) return;
        if (sPipScaleParent != null) {
            sPipScaleParent.removeOnLayoutChangeListener(sPipScaleListener);
        }
        sPipScaleParent = parent;
        parent.addOnLayoutChangeListener(sPipScaleListener);
    }

    /** 把钉住的整页缩进当前窗口。比例一致时刚好铺满，不一致时留出空边。 */
    private static void fitPageScale(View page) {
        if (!sPipChrome || sPipPageWidth <= 0 || sPipPageHeight <= 0) return;
        if (!(page.getParent() instanceof View)) return;
        View parent = (View) page.getParent();
        int pw = parent.getWidth();
        int ph = parent.getHeight();
        if (pw <= 0 || ph <= 0) return;
        // 窗口还没缩小（刚钉住尺寸、还没进小窗）时保持原样，避免整页先被放大一帧。
        if (pw >= sPipPageWidth && ph >= sPipPageHeight) {
            page.setScaleX(1f);
            page.setScaleY(1f);
            page.setTranslationX(0f);
            page.setTranslationY(0f);
            return;
        }
        float scale = Math.min(pw / (float) sPipPageWidth, ph / (float) sPipPageHeight);
        if (scale <= 0f || scale > 1f) return;
        page.setPivotX(0f);
        page.setPivotY(0f);
        page.setScaleX(scale);
        page.setScaleY(scale);
        float dx = (pw - sPipPageWidth * scale) / 2f;
        float dy = (ph - sPipPageHeight * scale) / 2f;
        page.setTranslationX(dx - page.getLeft());
        page.setTranslationY(dy - page.getTop());
        if (Math.abs(scale - sPipLoggedScale) > 0.02f) {
            sPipLoggedScale = scale;
            logi(
                    "pip scale",
                    sPipPageWidth + "x" + sPipPageHeight,
                    "into",
                    pw + "x" + ph,
                    "scale",
                    String.format(java.util.Locale.US, "%.3f", scale));
        }
    }

    /** 进小窗前记下 Blink 视口。当时键盘还开着就把被吃掉的高度补回来。 */
    private static void captureWebContentsSize() {
        WebContents web = currentWebContents();
        if (web == null) return;
        int w = web.getWidth();
        int h = web.getHeight();
        if (w < 600 || h < 600) return;
        int ime = imeBottom();
        if (ime > 0 && sPipPageHeight > h) {
            int restored = Math.min(sPipPageHeight, h + ime);
            if (restored > h) h = restored;
        }
        sPipWebWidth = w;
        sPipWebHeight = h;
    }

    /** 键盘收起后视口变高：改记这一份，别把短视口钉死。 */
    private static void adoptTallerWebContents() {
        if (sPipWebWidth <= 0 || sPipWebHeight <= 0) return;
        WebContents web = currentWebContents();
        if (web == null) return;
        int h = web.getHeight();
        if (web.getWidth() != sPipWebWidth || h <= sPipWebHeight) return;
        if (sPipPageHeight > 0 && h > sPipPageHeight) h = sPipPageHeight;
        sPipWebHeight = h;
    }

    /** 画中画里不留键盘。它一弹起，整页缩放的下半截就会空掉或被挡住。 */
    private static void hidePipIme() {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (controller != null) controller.hide(WindowInsets.Type.ime());
        }
        InputMethodManager imm =
                (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        View decor = activity.getWindow().getDecorView();
        if (decor.getWindowToken() != null) imm.hideSoftInputFromWindow(decor.getWindowToken(), 0);
    }

    private static int imeBottom() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0;
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return 0;
        try {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            if (insets == null) return 0;
            return insets.getInsets(WindowInsets.Type.ime()).bottom;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static void watchPipIme() {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return;
        View decor = activity.getWindow().getDecorView();
        if (sPipImeLayout != null && sPipImeDecor == decor) return;
        unwatchPipIme();
        sPipImeDecor = decor;
        sPipImeLayout =
                () -> {
                    if (!sPipChrome) return;
                    int ime = imeBottom();
                    if (ime == sPipImeSeen) return;
                    sPipImeSeen = ime;
                    if (ime > 0) {
                        hidePipIme();
                        keepWebContentsFullSize();
                        return;
                    }
                    adoptTallerWebContents();
                    View page = compositor();
                    if (page != null) fitPageScale(page);
                    keepWebContentsFullSize();
                };
        decor.getViewTreeObserver().addOnGlobalLayoutListener(sPipImeLayout);
    }

    private static void unwatchPipIme() {
        View decor = sPipImeDecor;
        ViewTreeObserver.OnGlobalLayoutListener listener = sPipImeLayout;
        sPipImeDecor = null;
        sPipImeLayout = null;
        if (decor == null || listener == null) return;
        ViewTreeObserver observer = decor.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnGlobalLayoutListener(listener);
    }

    /**
     * 小窗布局如果仍把网页尺寸改小，拉回进窗前的视口，避免 Blink 按窄视口重排。
     */
    private static void keepWebContentsFullSize() {
        if (sPipWebWidth <= 0 || sPipWebHeight <= 0) return;
        try {
            WebContents web = currentWebContents();
            if (web == null) return;
            if (web.getWidth() == sPipWebWidth && web.getHeight() == sPipWebHeight) return;
            web.setSize(sPipWebWidth, sPipWebHeight);
        } catch (RuntimeException e) {
            logi("pip page size", e.getMessage());
        }
    }

    private static @Nullable WebContents currentWebContents() {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return null;
        try {
            Tab tab = activity.getActivityTab();
            if (tab == null) return null;
            return tab.getWebContents();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void unclip(View page) {
        if (sPipClipCount > 0) return;
        View v = page;
        for (int i = 0; i < sPipClipViews.length && v instanceof ViewGroup; i++) {
            ViewGroup group = (ViewGroup) v;
            sPipClipViews[i] = group;
            sPipClipChildren[i] = group.getClipChildren();
            sPipClipToPadding[i] = group.getClipToPadding();
            group.setClipChildren(false);
            group.setClipToPadding(false);
            sPipClipCount++;
            if (!(group.getParent() instanceof View)) break;
            v = (View) group.getParent();
        }
    }

    private static void restoreClip() {
        for (int i = 0; i < sPipClipCount; i++) {
            View v = sPipClipViews[i];
            if (v instanceof ViewGroup) {
                ((ViewGroup) v).setClipChildren(sPipClipChildren[i]);
                ((ViewGroup) v).setClipToPadding(sPipClipToPadding[i]);
            }
            sPipClipViews[i] = null;
        }
        sPipClipCount = 0;
    }

    private static int[] pagePixelSize() {
        if (sPipPageWidth > 0 && sPipPageHeight > 0) {
            return new int[] {sPipPageWidth, sPipPageHeight};
        }
        View page = compositor();
        if (page == null) return new int[] {0, 0};
        return new int[] {page.getWidth(), page.getHeight()};
    }

    private static @Nullable CompositorViewHolder compositor() {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null || activity.isDestroyed()) return null;
        try {
            return activity.getCompositorViewHolderSupplier().get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 静画面上的那一句跟着当前回复走。没有静画面时是空操作。 */
    public static void refreshPipStatus() {
        if (!sPipChrome) return;
        FrameLayout card = sPipCard;
        if (card == null || card.getVisibility() != View.VISIBLE) return;
        TextView status = card.findViewWithTag("status");
        if (status == null) return;
        String line = MusePage.pipStatus();
        if (TextUtils.isEmpty(line)) {
            status.setVisibility(View.GONE);
            return;
        }
        status.setText(line);
        status.setVisibility(View.VISIBLE);
    }

    /**
     * 没登录时输入框提交的内容：像网址就打开，否则用默认搜索引擎搜。盖着的对话让开，让用户看见页面。
     */
    /** 付款页开在当前标签上，对话盖层让开，设置页自己关掉。 */
    public static void openBillingPage(String url) {
        if (TextUtils.isEmpty(url)) return;
        // 付款页是往前走，不是从用量页返回。侧边栏不要留在打开的位置。
        MusePage.abandonDrawer();
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return;
        try {
            Tab tab = activity.getActivityTab();
            if (tab == null) return;
            sOverlaySection.remove(tab.getId());
            sWebCollapsed = false;
            tab.loadUrl(new LoadUrlParams(url));
            refresh();
        } catch (Exception e) {
            logi("openBillingPage", e.getMessage());
        }
    }

    public static void openTyped(Tab tab, String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return;
        try {
            String url = typedUrl(tab, text);
            if (TextUtils.isEmpty(url)) return;
            sOverlaySection.remove(tab.getId());
            sWebCollapsed = false;
            tab.loadUrl(new LoadUrlParams(url));
            refresh();
        } catch (Exception e) {
            logi("openTyped", e.getMessage());
        }
    }

    /** 有点、有协议、或是本机地址时当成网址；其余交给默认搜索引擎。 */
    private static String typedUrl(Tab tab, String text) {
        if (looksLikeUrl(text)) {
            GURL fixed = UrlFormatter.fixupUrl(text);
            if (fixed != null && fixed.isValid() && !fixed.getSpec().isEmpty()) return fixed.getSpec();
        }
        try {
            Profile profile = tab.getProfile();
            if (profile != null) {
                TemplateUrlService service = TemplateUrlServiceFactory.getForProfile(profile);
                if (service != null && service.isLoaded()) {
                    String search = service.getUrlForSearchQuery(text);
                    if (!TextUtils.isEmpty(search)) return search;
                }
            }
        } catch (Exception e) {
            logi("typed search", e.getMessage());
        }
        return "https://www.google.com/search?q=" + Uri.encode(text);
    }

    private static boolean looksLikeUrl(String text) {
        if (text.startsWith("http://") || text.startsWith("https://")
                || text.startsWith("chrome://") || text.startsWith("chrome-native://")
                || text.startsWith("about:") || text.startsWith("file:")) {
            return true;
        }
        if (text.indexOf(' ') >= 0 || text.indexOf('\n') >= 0) return false;
        if (text.startsWith("localhost") || text.startsWith("127.")) return true;
        int slash = text.indexOf('/');
        String host = slash >= 0 ? text.substring(0, slash) : text;
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        return host.indexOf('.') > 0 && !host.endsWith(".");
    }

    /** 对话框申请麦克风：和网页权限走同一条系统弹窗。 */
    public static @Nullable WindowAndroid windowAndroid() {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        return activity.getWindowAndroid();
    }

    /** 对话框右上角：新开一个标签页，并给它一轮新的会话。前台切过去。 */
    public static void openNewSession() {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return;
        try {
            activity.getCurrentTabCreator()
                    .launchUrl(MuseUrls.newChatUrl(), TabLaunchType.FROM_CHROME_UI);
        } catch (Exception e) {
            logi("openNewSession", e.getMessage());
        }
    }

    /**
     * 在 Aero 自己的标签页里打开一个网页，并切到前台。
     * 登录这类必须让用户看见的页面用这个，不交给系统里的别的浏览器。
     */
    public static boolean openPage(String url) {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null || TextUtils.isEmpty(url)) return false;
        try {
            activity.getCurrentTabCreator().launchUrl(url, TabLaunchType.FROM_CHROME_UI);
            return true;
        } catch (Exception e) {
            logi("openPage", e.getMessage());
            return false;
        }
    }

    /** 覆盖层里的链接：开新标签页。 */
    public static void openInNewTab(String url) {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return;
        try {
            activity.getCurrentTabCreator().launchUrl(url, TabLaunchType.FROM_LINK);
        } catch (Exception e) {
            logi("openInNewTab", e.getMessage());
        }
    }

    /** 当前前台是不是正盖着附件预览、设置抽屉、推荐、选择框、社区，或者 Agent（返回键要先收它们）。 */
    private static boolean overlayVisible() {
        if (MusePage.chatSheetsOpen()) return true;
        if (MusePage.previewOpen()) return true;
        if (MusePage.drawerOpen()) return true;
        if (MusePage.filesOpen()) return true;
        if (MusePage.archiveOpen()) return true;
        if (MusePage.computerOpen()) return true;
        if (MusePage.communityOpen()) return true;
        return sOverlayAttached != null && sOverlayHost != null
                && sOverlayHost.getVisibility() == View.VISIBLE;
    }

    /** 返回键：先收附件预览和设置抽屉，再收推荐和选择框，再收盖层。从侧边栏点进去的盖层，关掉后抽屉回到打开的状态。 */
    private static boolean consumeBack() {
        if (LemurXWelcome.consumeBack()) return true;
        if (MusePage.closePreview()) return true;
        if (MusePage.closeDrawer()) return true;
        if (MusePage.closeChatSheets()) return true;
        if (MusePage.backFromComputer()) return true;
        if (MusePage.closeFiles()) return true;
        if (MusePage.backFromArchive()) return true;
        if (MusePage.backFromCommunity()) return true;
        if (!overlayVisible()) return false;
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return false;
        try {
            Tab current = activity.getActivityTab();
            if (current != null && sOverlaySection.remove(current.getId()) != null) {
                sWebCollapsed = false;
                refresh();
                return true;
            }
        } catch (Exception e) {
            logi("consumeBack", e.getMessage());
        }
        return false;
    }

    /** 前台是不是一张真正的网页。画中画有网页时只留这一页。 */
    private static boolean foregroundHasWeb() {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return false;
        try {
            if (!activity.areTabModelsInitialized()) return false;
            Tab tab = activity.getActivityTab();
            if (tab == null || tab.getUrl() == null) return false;
            String url = tab.getUrl().getSpec();
            if (url.startsWith("http://") || url.startsWith("https://")) return true;
            return tabWebUrl(tab) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 没有网页可看时的画中画：只有图标、名字和一句状态，没有按钮。 */
    private static void showPipCard() {
        ChromeTabbedActivity activity = sActivity;
        if (activity == null) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        if (sPipCard == null) {
            sPipCard = buildPipCard(activity);
            content.addView(
                    sPipCard,
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
        }
        sPipCard.setVisibility(View.VISIBLE);
        sPipCard.bringToFront();
        refreshPipStatus();
    }

    private static void hidePipCard() {
        FrameLayout card = sPipCard;
        if (card != null) card.setVisibility(View.GONE);
    }

    private static FrameLayout buildPipCard(Context context) {
        FrameLayout card = new FrameLayout(context);
        card.setBackgroundColor(MusePage.sheet(context));
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView icon = new ImageView(context);
        icon.setImageResource(R.mipmap.app_icon);
        int iconSize = Math.round(36 * context.getResources().getDisplayMetrics().density);
        column.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        TextView name = new TextView(context);
        name.setText(R.string.lemurx_muse_brand);
        name.setTextColor(MusePage.text(context));
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams nameLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = Math.round(10 * context.getResources().getDisplayMetrics().density);
        column.addView(name, nameLp);
        TextView status = new TextView(context);
        status.setTag("status");
        status.setTextColor(MusePage.muted(context));
        status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        status.setGravity(Gravity.CENTER);
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams statusLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = Math.round(6 * context.getResources().getDisplayMetrics().density);
        int side = Math.round(24 * context.getResources().getDisplayMetrics().density);
        statusLp.leftMargin = side;
        statusLp.rightMargin = side;
        column.addView(status, statusLp);
        card.addView(
                column,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER));
        return card;
    }

    /** 让容器里挂着的是 tab 的覆盖层页面（null = 收起）。 */
    private static void syncOverlay(@Nullable Tab tab) {
        FrameLayout host = sOverlayHost;
        if (host == null) return;
        // 画中画不把对话缩进去。页面对象留着，退出后再挂回来。
        if (sPipChrome) {
            if (host.getVisibility() != View.GONE) host.setVisibility(View.GONE);
            return;
        }
        MusePage page = tab != null ? sOverlays.get(tab.getId()) : null;
        if (page == null) {
            if (sOverlayAttached != null || host.getVisibility() != View.GONE) {
                rememberAttachedChat();
                detachOverlayView();
                host.setVisibility(View.GONE);
                concealCompositorForIme(false);
                // 没有内置 handler 在这一刻改状态，要自己告诉返回键管理器重新算一遍。
                BackPressManager.lemurXConsoleStateChanged();
            }
            return;
        }
        if (sOverlayAttached != page) {
            rememberAttachedChat();
            detachOverlayView();
            View view = page.getView();
            if (view != null) {
                if (view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
                host.addView(
                        view,
                        new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
            }
            sOverlayAttached = page;
        }
        if (host.getVisibility() != View.VISIBLE) {
            applyOverlayIme(host, null);
            host.setVisibility(View.VISIBLE);
            host.bringToFront();
            if (sNav != null) sNav.bringToFront();
            LemurXDeviceBridge.keepLockOnTop();
            hideProgress();
            BackPressManager.lemurXConsoleStateChanged();
        }
        host.post(
                () -> {
                    if (sOverlayHost == host) rememberAttachedChat();
                });
    }

    /** 覆盖层底部留出软键盘的高度；insets 为 null 时用窗口当前的那份。 */
    private static void applyOverlayIme(View host, @Nullable WindowInsets insets) {
        int ime = 0;
        try {
            WindowInsets source = insets != null ? insets : host.getRootWindowInsets();
            if (source != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ime = source.getInsets(WindowInsets.Type.ime()).bottom;
            }
        } catch (Exception e) {
            logi("applyOverlayIme", e.getMessage());
        }
        if (host.getPaddingBottom() != ime) host.setPadding(0, 0, 0, ime);
        concealCompositorForIme(host.getVisibility() == View.VISIBLE && ime > 0);
    }

    /**
     * 对话盖在网页上时，键盘一打开网页合成层会跟着视口往上挪，从垫高的那一截里露出来。
     * 键盘开着就先把合成层藏住，合上再放回来。对话本身不在这一层里。
     */
    private static void concealCompositorForIme(boolean conceal) {
        CompositorViewHolder holder = compositor();
        if (holder == null) {
            sCompositorHiddenForIme = false;
            return;
        }
        if (conceal) {
            if (sCompositorHiddenForIme) return;
            sCompositorHiddenForIme = true;
            holder.setVisibility(View.INVISIBLE);
            return;
        }
        if (!sCompositorHiddenForIme) return;
        sCompositorHiddenForIme = false;
        holder.setVisibility(View.VISIBLE);
    }

    private static void detachOverlayView() {
        FrameLayout host = sOverlayHost;
        if (host != null) host.removeAllViews();
        sOverlayAttached = null;
    }

    // ---------------------------------------------------------------- 顶部进度条

    /** 前台这个标签页是不是正显示着一张网页（不是原生页面）。 */
    private static boolean isForegroundWeb(ChromeTabbedActivity activity, Tab tab) {
        try {
            return tab == activity.getActivityTab()
                    && !tab.isNativePage()
                    && !MuseUrls.isMuse(tab.getUrl().getSpec());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * refresh 时对齐进度条：浏览布局下前台网页还在加载就画（含从标签页管理 / 新标签动画回来、
     * 或者加载期间没有新的进度事件的情况），否则收起。
     */
    private static void syncProgress(ChromeTabbedActivity activity) {
        if (sPipChrome) {
            hideProgress();
            return;
        }
        Tab tab = null;
        try {
            if (activity.areTabModelsInitialized()) tab = activity.getActivityTab();
        } catch (Exception e) {
            tab = null;
        }
        if (sLayout == LayoutType.BROWSING && tab != null && isForegroundWeb(activity, tab)
                && sOverlayAttached == null && tab.isLoading()) {
            showProgress(tab, Math.max(tab.getProgress(), 0.05f));
        } else if (sProgressTabId != -1) {
            hideProgress();
        }
    }

    /** 前台网页标签页在加载：按进度画线。原生页面、后台标签页、非浏览布局（标签页管理）不画。 */
    private static void showProgress(Tab tab, float progress) {
        ChromeTabbedActivity activity = sActivity;
        View bar = sProgress;
        if (activity == null || bar == null) return;
        if (sLayout != LayoutType.BROWSING || !isForegroundWeb(activity, tab)) return;
        if (sOverlayAttached != null) return;
        if (progress >= 1f) {
            finishProgress(tab);
            return;
        }
        if (sProgressTabId != tab.getId()) {
            sProgressTabId = tab.getId();
            bar.animate().cancel();
            bar.setAlpha(1f);
            bar.setScaleX(0f);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
            if (lp != null) {
                lp.topMargin = statusInsetPx();
                bar.setLayoutParams(lp);
            }
            bar.setVisibility(View.VISIBLE);
            bar.bringToFront();
            if (sNav != null && sNav.getVisibility() == View.VISIBLE) sNav.bringToFront();
            LemurXDeviceBridge.keepLockOnTop();
        }
        float target = Math.max(bar.getScaleX(), Math.min(Math.max(progress, 0.1f), 0.98f));
        bar.animate().cancel();
        bar.animate()
                .scaleX(target)
                .setDuration(120)
                .setInterpolator(new android.view.animation.LinearInterpolator())
                .withEndAction(() -> creepProgress(bar))
                .start();
    }

    /** 进度事件之间慢慢往前爬，爬到 PROGRESS_CREEP_TO 就停（真正的进度事件会接管）。 */
    private static void creepProgress(View bar) {
        if (sProgressTabId == -1 || bar.getScaleX() >= PROGRESS_CREEP_TO) return;
        bar.animate()
                .scaleX(PROGRESS_CREEP_TO)
                .setDuration(PROGRESS_CREEP_MS)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f))
                .withEndAction(null)
                .start();
    }

    /** 加载完了：走满后淡出。 */
    private static void finishProgress(Tab tab) {
        View bar = sProgress;
        if (bar == null || tab.getId() != sProgressTabId) return;
        sProgressTabId = -1;
        bar.animate().cancel();
        bar.animate()
                .scaleX(1f)
                .setDuration(100)
                .withEndAction(
                        () -> {
                            if (sProgressTabId != -1) return;
                            bar.animate()
                                    .alpha(0f)
                                    .setDuration(PROGRESS_FADE_MS)
                                    .withEndAction(
                                            () -> {
                                                if (sProgressTabId == -1) {
                                                    bar.setVisibility(View.GONE);
                                                    bar.setScaleX(0f);
                                                    bar.setAlpha(1f);
                                                }
                                            })
                                    .start();
                        })
                .start();
    }

    private static void hideProgress() {
        View bar = sProgress;
        sProgressTabId = -1;
        if (bar == null) return;
        bar.animate().cancel();
        bar.setVisibility(View.GONE);
        bar.setScaleX(0f);
        bar.setAlpha(1f);
    }

    /**
     * 显示前按窗口当前的手势条高度重算底部内边距。挂载时窗口可能还没派发过 insets，而且
     * 之后也未必再派发一次，所以不能只靠 OnApplyWindowInsetsListener。
     */
    private static void syncInsets(View view, int padH, int padTop, int padBottom) {
        try {
            WindowInsets root = view.getRootWindowInsets();
            int inset = root != null ? bottomInset(root) : 0;
            view.setPadding(padH, padTop, padH, padBottom + inset);
        } catch (Exception e) {
            logi("syncInsets", e.getMessage());
        }
    }

    private static void logi(String... parts) {
        Log.i(TAG, "%s", TextUtils.join(" ", parts));
    }
}
