// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.chromium.base.Callback;
import org.chromium.base.CommandLine;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.ObserverList;
import org.chromium.base.ThreadUtils;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.feed.FeedActionDelegate;
import org.chromium.chrome.browser.feed.FeedListContentManager;
import org.chromium.chrome.browser.feed.FeedListContentManager.FeedContent;
import org.chromium.chrome.browser.feed.FeedListContentManager.NativeViewContent;
import org.chromium.chrome.browser.feed.FeedReliabilityLogger;
import org.chromium.chrome.browser.feed.FeedScrollState;
import org.chromium.chrome.browser.feed.Stream;
import org.chromium.chrome.browser.feed.StreamKind;
import org.chromium.chrome.browser.xsurface.HybridListRenderer;
import org.chromium.chrome.browser.xsurface.feed.FeedSurfaceScope;
import org.chromium.components.browser_ui.styles.SemanticColorUtils;
import org.chromium.content_public.browser.LoadUrlParams;
import org.chromium.ui.base.PageTransition;
import org.chromium.ui.mojom.WindowOpenDisposition;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

/**
 * LemurX 新标签页「探索」区的内容流：把 Lemur 新闻服务的数据套进 Chromium 的 Feed 框架。
 *
 * <p>为什么需要它：开源 Chromium 的 Discover Feed 依赖闭源的 xsurface 渲染器，公开构建里只有一个
 * 桩实现，所以上游 {@code FeedStream} 永远只能显示「无法刷新」的零状态。这个类实现同一个
 * {@link Stream} 接口，由 {@code FeedSurfaceCoordinator.createFeedStream()} 在
 * {@link #isEnabled()} 为真时替代 {@code FeedStream}。上游的头部（"探索"标题、开关、隐藏 Feed 的
 * 偏好）全部保留，只有列表内容换成我们自己的原生卡片。
 *
 * <p>数据源与样式均照搬 Lemur（{@code ~/code/lemur_project}）现有的 Discover：
 *
 * <ul>
 *   <li>接口：{@code GET {base}/lemur/news/meta} 取国家/语言/分类，{@code GET
 *       {base}/lemur/news/headlines?country&lang&category&page&pageSize} 分页取文章。
 *   <li>卡片：左侧标题（16sp，最多 3 行）+ 右侧 98×76dp 圆角 12dp 缩略图，下方 10sp 次要文字
 *       「来源 · 时间」，卡片间距 16dp。滚动到底部自动加载下一页。
 * </ul>
 *
 * <p>与 Lua 的关系：这里不经过 Lua 运行时，Lua 总开关关掉后新闻依旧可用；Lua 若想接管首页，
 * 应该用 {@code lemurx.skin} / {@code lemurx.ui} 覆盖，而不是由这里让路。
 *
 * <p>线程：所有 UI 与状态操作都在 UI 线程；网络请求在 ThreadPool 里跑，结果投回 UI 线程，并用
 * 请求序号丢弃过期响应，避免 unbind 后仍然改列表。
 */
public class LemurXDiscoverStream implements Stream {
    private static final String TAG = "LemurXDiscover";

    /** 调试环境（用户的局域网新闻服务）。 */
    static final String DEBUG_BASE_URL = "http://192.168.1.111:18888/";

    /** Lemur 正式环境。 */
    static final String RELEASE_BASE_URL = "https://api.lemurbrowser.com/";

    /** lemurx_settings 里的键：总开关（默认开）与数据源地址（默认见 {@link #baseUrl()}）。 */
    static final String KEY_ENABLED = "discover.enabled";

    static final String KEY_BASE_URL = "discover.base_url";

    /** 命令行覆盖：{@code --lemurx-discover-url=http://host:port/}。 */
    private static final String SWITCH_BASE_URL = "lemurx-discover-url";

    private static final int PAGE_SIZE = 50;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 3 * 1024 * 1024;
    private static final String KEY_FOOTER = "lemurx-discover-footer";
    private static final String KEY_ARTICLE_PREFIX = "lemurx-discover-article-";

    /** 缩略图内存缓存，跨 NTP 实例共享（NTP 每开一个新标签页就重建一次）。 */
    private static final LruCache<String, Bitmap> sImageCache =
            new LruCache<String, Bitmap>(24 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    /** 一条新闻。字段名与 Lemur 的 {@code NewsArticleBean} 一致。 */
    static final class Article {
        String id;
        String title;
        String description;
        String image;
        String publishedAt;
        String sourceName;
        String sourceUrl;
        String url;

        static Article fromJson(JSONObject o) {
            Article a = new Article();
            a.id = o.optString("id", "");
            a.title = o.optString("title", "");
            a.description = o.optString("description", "");
            a.image = o.optString("image", "");
            a.publishedAt = o.optString("publishedAt", "");
            a.sourceName = o.optString("sourceName", "");
            a.sourceUrl = o.optString("sourceUrl", "");
            a.url = o.optString("url", "");
            return a;
        }

        String openUrl() {
            return !TextUtils.isEmpty(url) ? url : sourceUrl;
        }
    }

    private final Activity mActivity;
    private final FeedActionDelegate mActionDelegate;
    private final StreamsMediator mStreamsMediator;
    private final ObserverList<ContentChangedListener> mContentChangedListeners =
            new ObserverList<>();
    private final int mLateralPaddingsPx;

    // 绑定状态。
    private RecyclerView mRecyclerView;
    private FeedListContentManager mContentManager;
    private int mHeaderCount;
    private final RecyclerView.OnScrollListener mScrollListener =
            new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                    if (dy > 0) maybeLoadMore();
                }
            };

    // 数据状态。
    private String mCountry = "us";
    private String mLang = "en";
    private String mCategory = "general";
    private int mPage;
    private int mTotal = -1;
    private boolean mHasMore = true;
    private boolean mLoading;
    private boolean mMetaResolved;
    private boolean mLastRequestFailed;
    private int mRequestSeq;
    private long mLastFetchTimeMs;
    private final List<Article> mArticles = new ArrayList<>();
    private final Map<String, NativeViewContent> mContentByKey = new HashMap<>();
    private NativeViewContent mFooterContent;
    private TextView mFooterText;

    public LemurXDiscoverStream(
            Activity activity,
            FeedActionDelegate actionDelegate,
            StreamsMediator streamsMediator,
            int lateralPaddingsPx) {
        mActivity = activity;
        mActionDelegate = actionDelegate;
        mStreamsMediator = streamsMediator;
        mLateralPaddingsPx = lateralPaddingsPx;
    }

    // ---------------------------------------------------------------- 静态配置

    private static SharedPreferences prefs() {
        return ContextUtils.getApplicationContext()
                .getSharedPreferences(LemurXScripts.PREF_FILE, Context.MODE_PRIVATE);
    }

    /** 是否用 LemurX 新闻流替代上游 FeedStream。默认开；关掉即回到上游行为。 */
    public static boolean isEnabled() {
        try {
            return prefs().getBoolean(KEY_ENABLED, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /**
     * 数据源根地址，末尾带 "/"。优先级：命令行开关 > lemurx_settings > 内置默认值。
     *
     * <p>目前默认指向调试服务 {@link #DEBUG_BASE_URL}；切正式环境时把默认值换成
     * {@link #RELEASE_BASE_URL} 或者在设置里写 {@link #KEY_BASE_URL}。
     */
    public static String baseUrl() {
        String url = null;
        try {
            CommandLine cl = CommandLine.getInstance();
            if (cl.hasSwitch(SWITCH_BASE_URL)) url = cl.getSwitchValue(SWITCH_BASE_URL);
        } catch (Throwable ignored) {
        }
        if (TextUtils.isEmpty(url)) {
            try {
                url = prefs().getString(KEY_BASE_URL, null);
            } catch (Throwable ignored) {
            }
        }
        if (TextUtils.isEmpty(url)) url = DEBUG_BASE_URL;
        return url.endsWith("/") ? url : url + "/";
    }

    public static void setBaseUrl(String url) {
        SharedPreferences.Editor e = prefs().edit();
        if (TextUtils.isEmpty(url)) {
            e.remove(KEY_BASE_URL);
        } else {
            e.putString(KEY_BASE_URL, url);
        }
        e.apply();
    }

    // ---------------------------------------------------------------- Stream

    @Override
    public @StreamKind int getStreamKind() {
        return StreamKind.FOR_YOU;
    }

    @Override
    public void restoreSavedInstanceState(FeedScrollState scrollState) {
        if (scrollState == null || mRecyclerView == null) return;
        RecyclerView.LayoutManager lm = mRecyclerView.getLayoutManager();
        if (lm instanceof LinearLayoutManager && scrollState.position >= 0) {
            ((LinearLayoutManager) lm)
                    .scrollToPositionWithOffset(scrollState.position, scrollState.offset);
        }
    }

    @Override
    public void notifyNewHeaderCount(int newHeaderCount) {
        mHeaderCount = newHeaderCount;
    }

    @Override
    public void addOnContentChangedListener(ContentChangedListener listener) {
        mContentChangedListeners.addObserver(listener);
    }

    @Override
    public void removeOnContentChangedListener(ContentChangedListener listener) {
        mContentChangedListeners.removeObserver(listener);
    }

    @Override
    public void triggerRefresh(Callback<Boolean> callback) {
        refresh();
        if (callback != null) callback.onResult(true);
    }

    @Override
    public long getLastFetchTimeMs() {
        return mLastFetchTimeMs;
    }

    @Override
    public void bind(
            RecyclerView view,
            FeedListContentManager manager,
            FeedScrollState savedInstanceState,
            FeedSurfaceScope surfaceScope,
            HybridListRenderer renderer,
            FeedReliabilityLogger reliabilityLogger,
            int headerCount) {
        ThreadUtils.assertOnUiThread();
        mRecyclerView = view;
        mContentManager = manager;
        mHeaderCount = headerCount;
        view.addOnScrollListener(mScrollListener);
        render();
        if (mArticles.isEmpty() && !mLoading) {
            refresh();
        }
        if (savedInstanceState != null) restoreSavedInstanceState(savedInstanceState);
    }

    @Override
    public void unbind(boolean shouldPlaceSpacer, boolean switchingStream) {
        ThreadUtils.assertOnUiThread();
        if (mRecyclerView == null || mContentManager == null) return;
        mRecyclerView.removeOnScrollListener(mScrollListener);
        List<FeedContent> list = new ArrayList<>();
        if (shouldPlaceSpacer) {
            View spacer = new View(mActivity);
            spacer.setLayoutParams(
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100)));
            list.add(new NativeViewContent(0, "lemurx-discover-spacer", spacer));
        }
        replaceContent(list);
        mRecyclerView = null;
        mContentManager = null;
    }

    @Override
    public String getContentState() {
        return "";
    }

    @Override
    public void destroy() {
        // 让在途请求作废；视图交给 RecyclerView 回收。
        mRequestSeq++;
        mLoading = false;
    }

    // ---------------------------------------------------------------- 数据加载

    private void refresh() {
        mRequestSeq++;
        mLoading = false;
        mLastRequestFailed = false;
        if (!mMetaResolved) {
            loadMeta();
        } else {
            fetchHeadlines(/* refresh= */ true);
        }
    }

    private void maybeLoadMore() {
        if (mRecyclerView == null || mLoading || !mHasMore || mPage < 1 || mLastRequestFailed) {
            return;
        }
        RecyclerView.LayoutManager lm = mRecyclerView.getLayoutManager();
        if (!(lm instanceof LinearLayoutManager)) return;
        int last = ((LinearLayoutManager) lm).findLastVisibleItemPosition();
        int total = lm.getItemCount();
        if (last >= 0 && last >= total - 4) {
            fetchHeadlines(/* refresh= */ false);
        }
    }

    private void loadMeta() {
        mLoading = true;
        final int seq = mRequestSeq;
        updateFooter();
        final String url = baseUrl() + "lemur/news/meta";
        PostTask.postTask(
                TaskTraits.USER_VISIBLE_MAY_BLOCK,
                () -> {
                    JSONObject meta = null;
                    try {
                        JSONObject envelope = new JSONObject(fetchText(url));
                        meta = envelope.optJSONObject("data");
                        if (meta == null && envelope.has("defaultTarget")) meta = envelope;
                    } catch (Throwable t) {
                        Log.w(TAG, "meta failed: %s", t.toString());
                    }
                    final JSONObject result = meta;
                    PostTask.postTask(TaskTraits.UI_DEFAULT, () -> onMeta(seq, result));
                });
    }

    private void onMeta(int seq, JSONObject meta) {
        if (seq != mRequestSeq) return;
        mLoading = false;
        Locale locale = Locale.getDefault();
        String country = lower(locale.getCountry());
        String lang = lower(locale.getLanguage());
        String pickedCountry = null;
        String pickedLang = null;
        if (meta != null) {
            JSONArray targets = meta.optJSONArray("targets");
            String byCountryLang = null;
            if (targets != null) {
                for (int i = 0; i < targets.length(); i++) {
                    JSONObject t = targets.optJSONObject(i);
                    if (t == null) continue;
                    String tc = lower(t.optString("country"));
                    String tl = lower(t.optString("lang"));
                    if (tc.isEmpty() || tl.isEmpty()) continue;
                    if (tc.equals(country) && tl.equals(lang)) {
                        pickedCountry = tc;
                        pickedLang = tl;
                        break;
                    }
                    if (byCountryLang == null && tc.equals(country)) byCountryLang = tl;
                }
            }
            if (pickedCountry == null && byCountryLang != null) {
                pickedCountry = country;
                pickedLang = byCountryLang;
            }
            if (pickedCountry == null) {
                JSONObject def = meta.optJSONObject("defaultTarget");
                if (def != null) {
                    String dc = lower(def.optString("country"));
                    String dl = lower(def.optString("lang"));
                    if (!dc.isEmpty() && !dl.isEmpty()) {
                        pickedCountry = dc;
                        pickedLang = dl;
                    }
                }
            }
            JSONArray categories = meta.optJSONArray("categories");
            if (categories != null && categories.length() > 0) {
                String c = categories.optString(0, "");
                if (!TextUtils.isEmpty(c)) mCategory = c;
            }
        }
        mCountry = pickedCountry != null ? pickedCountry : "us";
        mLang = pickedLang != null ? pickedLang : "en";
        // meta 拿不到也用默认目标继续拉；只有 headlines 失败才算失败。
        mMetaResolved = meta != null;
        fetchHeadlines(/* refresh= */ true);
    }

    private void fetchHeadlines(boolean refresh) {
        if (mLoading) return;
        final int page = refresh ? 1 : mPage + 1;
        if (!refresh && (mPage < 1 || !mHasMore)) return;
        mLoading = true;
        mLastRequestFailed = false;
        final int seq = mRequestSeq;
        updateFooter();
        final String url =
                baseUrl()
                        + "lemur/news/headlines?country="
                        + Uri.encode(mCountry)
                        + "&lang="
                        + Uri.encode(mLang)
                        + "&category="
                        + Uri.encode(mCategory)
                        + "&page="
                        + page
                        + "&pageSize="
                        + PAGE_SIZE;
        PostTask.postTask(
                TaskTraits.USER_VISIBLE_MAY_BLOCK,
                () -> {
                    List<Article> articles = null;
                    int total = -1;
                    try {
                        JSONObject envelope = new JSONObject(fetchText(url));
                        JSONObject body = envelope.optJSONObject("data");
                        if (body == null) body = envelope;
                        JSONArray arr = body.optJSONArray("articles");
                        articles = new ArrayList<>();
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject o = arr.optJSONObject(i);
                                if (o == null) continue;
                                Article a = Article.fromJson(o);
                                if (!TextUtils.isEmpty(a.title)) articles.add(a);
                            }
                        }
                        total = body.optInt("total", -1);
                    } catch (Throwable t) {
                        Log.w(TAG, "headlines failed (%s): %s", url, t.toString());
                        articles = null;
                    }
                    final List<Article> result = articles;
                    final int resultTotal = total;
                    PostTask.postTask(
                            TaskTraits.UI_DEFAULT,
                            () -> onHeadlines(seq, refresh, page, result, resultTotal));
                });
    }

    private void onHeadlines(
            int seq, boolean refresh, int page, List<Article> articles, int total) {
        if (seq != mRequestSeq) return;
        mLoading = false;
        if (articles == null) {
            mLastRequestFailed = true;
            updateFooter();
            return;
        }
        mLastFetchTimeMs = System.currentTimeMillis();
        mPage = page;
        if (total > 0) mTotal = total;
        if (refresh) {
            mArticles.clear();
            mContentByKey.clear();
        }
        // 去重：服务端翻页偶尔会重复，按 id / url 过滤。
        for (Article a : articles) {
            String key = keyFor(a);
            if (mContentByKey.containsKey(key)) continue;
            mArticles.add(a);
            mContentByKey.put(key, buildCard(a, key));
        }
        mHasMore = articles.size() >= PAGE_SIZE && (mTotal <= 0 || mArticles.size() < mTotal);
        render();
        notifyContentChanged();
        // 首屏可能不满一屏，继续补一页。
        if (mRecyclerView != null) mRecyclerView.post(this::maybeLoadMore);
    }

    private void notifyContentChanged() {
        List<FeedContent> contents =
                mContentManager != null ? mContentManager.getContentList() : null;
        for (ContentChangedListener l : mContentChangedListeners) {
            l.onContentChanged(contents);
        }
    }

    // ---------------------------------------------------------------- 渲染

    private void render() {
        if (mContentManager == null) return;
        List<FeedContent> list = new ArrayList<>(mArticles.size() + 1);
        for (Article a : mArticles) {
            NativeViewContent c = mContentByKey.get(keyFor(a));
            if (c != null) list.add(c);
        }
        list.add(footerContent());
        replaceContent(list);
        updateFooter();
    }

    private void replaceContent(List<FeedContent> list) {
        if (mContentManager == null) return;
        int itemCount = mContentManager.getItemCount();
        int start = Math.min(mHeaderCount, itemCount);
        try {
            mContentManager.replaceRange(start, itemCount - start, list);
        } catch (Throwable t) {
            Log.w(TAG, "replaceRange failed: %s", t.toString());
        }
    }

    private static String keyFor(Article a) {
        String id = !TextUtils.isEmpty(a.id) ? a.id : a.openUrl();
        return KEY_ARTICLE_PREFIX + id;
    }

    private NativeViewContent footerContent() {
        if (mFooterContent == null) {
            TextView tv = new TextView(mActivity);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(12), 0, dp(16));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTextColor(SemanticColorUtils.getDefaultTextColorSecondary(mActivity));
            tv.setOnClickListener(
                    v -> {
                        if (mLoading) return;
                        if (mLastRequestFailed || mArticles.isEmpty()) {
                            refresh();
                        } else if (mHasMore) {
                            fetchHeadlines(/* refresh= */ false);
                        }
                    });
            mFooterText = tv;
            mFooterContent = new NativeViewContent(mLateralPaddingsPx, KEY_FOOTER, tv);
        }
        return mFooterContent;
    }

    private void updateFooter() {
        if (mFooterText == null) return;
        String text;
        boolean clickable;
        if (mLoading) {
            text = mActivity.getString(R.string.lemurx_discover_loading);
            clickable = false;
        } else if (mLastRequestFailed) {
            text =
                    mArticles.isEmpty()
                            ? mActivity.getString(R.string.lemurx_discover_failed_retry)
                            : mActivity.getString(R.string.lemurx_discover_load_more_failed);
            clickable = true;
        } else if (mArticles.isEmpty()) {
            text = mActivity.getString(R.string.lemurx_discover_empty);
            clickable = true;
        } else if (mHasMore) {
            text = mActivity.getString(R.string.lemurx_discover_load_more);
            clickable = true;
        } else {
            text = mActivity.getString(R.string.lemurx_discover_no_more);
            clickable = false;
        }
        mFooterText.setText(text);
        mFooterText.setClickable(clickable);
    }

    /** 按 Lemur 的 lemur_ntp_discover_item.xml 用代码搭一张卡片。 */
    private NativeViewContent buildCard(Article article, String key) {
        Context ctx = mActivity;
        int textPrimary = SemanticColorUtils.getDefaultTextColor(ctx);
        int textSecondary = SemanticColorUtils.getDefaultTextColorSecondary(ctx);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, 0, 0, dp(16)); // lemur_space_4
        card.setLayoutParams(
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);

        TextView title = new TextView(ctx);
        title.setText(article.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16); // TextAppearance.Lemur.Title2
        title.setTextColor(textPrimary);
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams titleLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginEnd(dp(12)); // lemur_space_3
        row.addView(title, titleLp);

        if (!TextUtils.isEmpty(article.image)) {
            ImageView thumb = new ImageView(ctx);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundColor(SemanticColorUtils.getColorSurfaceContainer(ctx));
            final int radius = dp(12); // super_radius = lemur_space_3
            thumb.setOutlineProvider(
                    new ViewOutlineProvider() {
                        @Override
                        public void getOutline(View view, Outline outline) {
                            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                        }
                    });
            thumb.setClipToOutline(true);
            row.addView(thumb, new LinearLayout.LayoutParams(dp(98), dp(76)));
            loadThumb(thumb, article.image);
        }
        card.addView(
                row,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView source = new TextView(ctx);
        source.setText(formatSource(article));
        source.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10); // TextAppearance.Lemur.Badge
        source.setTextColor(textSecondary);
        source.setIncludeFontPadding(false);
        source.setSingleLine(true);
        source.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sourceLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sourceLp.topMargin = dp(8); // lemur_space_2
        card.addView(source, sourceLp);

        final String url = article.openUrl();
        if (!TextUtils.isEmpty(url)) {
            card.setClickable(true);
            card.setOnClickListener(v -> openUrl(url));
        }
        card.setContentDescription(article.title);
        return new NativeViewContent(mLateralPaddingsPx, key, card);
    }

    private void openUrl(String url) {
        try {
            LoadUrlParams params = new LoadUrlParams(url, PageTransition.LINK);
            mActionDelegate.openUrl(WindowOpenDisposition.CURRENT_TAB, params);
        } catch (Throwable t) {
            Log.w(TAG, "openUrl failed: %s", t.toString());
        }
    }

    private String formatSource(Article a) {
        String name = a.sourceName == null ? "" : a.sourceName.trim();
        String time = relativeTime(a.publishedAt);
        if (!TextUtils.isEmpty(name) && !TextUtils.isEmpty(time)) return name + " · " + time;
        return !TextUtils.isEmpty(name) ? name : time;
    }

    private String relativeTime(String iso) {
        if (TextUtils.isEmpty(iso)) return "";
        long ts = parseIso8601(iso.trim());
        if (ts <= 0) return iso.trim();
        long now = System.currentTimeMillis();
        if (ts > now) ts = now;
        return DateUtils.getRelativeTimeSpanString(
                        ts, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
                .toString();
    }

    private static long parseIso8601(String s) {
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd HH:mm:ss"};
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                f.setLenient(false);
                Date d = f.parse(s, new ParsePosition(0));
                if (d != null) return d.getTime();
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private void loadThumb(ImageView target, String url) {
        Bitmap cached = sImageCache.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        target.setTag(url);
        final int reqW = dp(98) * 2;
        final int reqH = dp(76) * 2;
        PostTask.postTask(
                TaskTraits.BEST_EFFORT_MAY_BLOCK,
                () -> {
                    Bitmap bmp = null;
                    try {
                        byte[] bytes = fetchBytes(url, MAX_IMAGE_BYTES);
                        bmp = decodeScaled(bytes, reqW, reqH);
                    } catch (Throwable t) {
                        Log.d(TAG, "thumb failed: %s", t.toString());
                    }
                    final Bitmap result = bmp;
                    PostTask.postTask(
                            TaskTraits.UI_DEFAULT,
                            () -> {
                                if (result == null) return;
                                sImageCache.put(url, result);
                                if (url.equals(target.getTag())) target.setImageBitmap(result);
                            });
                });
    }

    private static Bitmap decodeScaled(byte[] bytes, int reqW, int reqH) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        int sample = 1;
        while (opts.outWidth / (sample * 2) >= reqW && opts.outHeight / (sample * 2) >= reqH) {
            sample *= 2;
        }
        BitmapFactory.Options real = new BitmapFactory.Options();
        real.inSampleSize = sample;
        real.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, real);
    }

    // ---------------------------------------------------------------- 网络

    private static String fetchText(String url) throws Exception {
        return new String(fetchBytes(url, MAX_JSON_BYTES), StandardCharsets.UTF_8);
    }

    private static byte[] fetchBytes(String url, int maxBytes) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setRequestProperty("User-Agent", "LemurX/Discover");
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new Exception("HTTP " + status);
            }
            InputStream in = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                in = new GZIPInputStream(in);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > maxBytes) throw new Exception("body too large");
            }
            return out.toByteArray();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------------------------------------------------------------- 工具

    private int dp(int v) {
        return Math.round(
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, v, mActivity.getResources().getDisplayMetrics()));
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.US);
    }
}
