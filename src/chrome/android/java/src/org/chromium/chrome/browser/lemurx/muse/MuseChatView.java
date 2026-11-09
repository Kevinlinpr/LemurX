// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.ArrowKeyMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ImageSpan;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.HapticFeedbackConstants;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.FileProvider;
import androidx.core.widget.ImageViewCompat;

import org.chromium.base.ContextUtils;
import org.chromium.base.FileProviderUtils;
import org.chromium.base.Log;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.back_press.BackPressManager;
import org.chromium.chrome.browser.lemurx.LemurXConsoleShell;
import org.chromium.chrome.browser.lemurx.agent.LemurXDeviceBridge;
import org.chromium.chrome.browser.lemurx.LemurXHaptics;
import org.chromium.chrome.browser.lemurx.LemurXKillSwitch;
import org.chromium.chrome.browser.omnibox.styles.OmniboxImageSupplier;
import org.chromium.chrome.browser.omnibox.styles.OmniboxResourceProvider;
import org.chromium.chrome.browser.omnibox.suggestions.AutocompleteController;
import org.chromium.chrome.browser.omnibox.suggestions.base.BaseSuggestionView;
import org.chromium.chrome.browser.ui.theme.BrandedColorScheme;
import org.chromium.components.browser_ui.styles.ChromeColors;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.search_engines.TemplateUrlServiceFactory;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.components.metrics.OmniboxEventProtosIntDef.PageClassification;
import org.chromium.components.omnibox.AutocompleteInput;
import org.chromium.components.omnibox.AutocompleteMatch;
import org.chromium.components.omnibox.AutocompleteResult;
import org.chromium.components.omnibox.AutocompleteStopReason;
import org.chromium.components.omnibox.OmniboxFocusReason;
import org.chromium.components.omnibox.OmniboxSuggestionType;
import org.chromium.components.omnibox.SuggestTemplateInfoProto.SuggestTemplateInfo;
import org.chromium.components.search_engines.TemplateUrl;
import org.chromium.components.search_engines.TemplateUrlService;
import org.chromium.url.GURL;
import org.chromium.ui.base.WindowAndroid;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 一轮对话的原生视图：消息列表 + 输入栏。
 *
 * <ul>
 *   <li>历史从 {@code GET /api/conversation?id=<thread>} 读，发送后和回复结束后
 *       {@code PUT /api/conversation} 写回（只存 user / assistant 文本，和网页版同一张表）。
 *   <li>回复走 {@link MuseApi#run}：逐 token 追加到最后一条助手消息，每帧最多重排一次 Markdown。
 *   <li>工具调用期间在列表底部显示"正在…"；{@link #liveStatus} 把这个状态暴露给活动页，
 *       活动页据此知道哪个标签页正在干什么。
 *   <li>页面文字都是不可信数据：Markdown 渲染只产生样式和 http(s) 链接。
 *   <li>空会话显示一句话 + 开场建议。建议先画兜底，再向 {@code GET /api/tab-starters}
 *       换该用户搜索习惯图上的动态条目（有网页的标签页第一条仍是"总结当前网页"）；
 *       列表没贴底时右下角浮一个"回到最新"按钮。
 *   <li>发送之后把键盘收起来。麦克风听完一句就发给这个标签页的 Agent。
 *   <li>输入栏可以附上图片、PDF 和文本。气泡里只画小图标和文件名，正文摘录只放进这一轮请求，
 *       不铺在对话里。文件内容按不可信资料处理。
 * </ul>
 */
@NullMarked
class MuseChatView extends FrameLayout {
    private static final String TAG = "LemurXMuse";
    private static final int TITLE_MAX = 40;

    /** 正在回复中的会话 → 状态文字；活动页读。 */
    private static final Map<String, String> sLive = new HashMap<>();

    /** 会话 id → 标题（第一条用户消息），给活动页里"标签页在干什么"的描述用。 */
    private static final Map<String, String> sTitles = new HashMap<>();

    /** 有对话正在回复或操作页面。画中画靠这个判断要不要在按 Home 时收成小窗。 */
    static boolean hasLiveWork() {
        return !sLive.isEmpty();
    }

    /** 画中画里没有网页可看时，用这一句当唯一的状态，不把整页按钮缩进去。 */
    static @Nullable String anyLiveStatus() {
        for (String status : sLive.values()) {
            if (!TextUtils.isEmpty(status)) return status;
        }
        return null;
    }

    static @Nullable String liveStatus(String threadId) {
        return sLive.get(threadId);
    }

    static @Nullable String titleOf(String threadId) {
        return sTitles.get(threadId);
    }

    private static final class Message {
        final String id;
        final String role;
        String text;
        final List<Attach> files;
        @Nullable TextView view;
        /** 助手回复的排版：段落、代码块、表格。用户气泡不用。 */
        @Nullable LinearLayout body;
        /** 正文和文件卡片分开挂。窗口滚动时整行一起进出。 */
        @Nullable LinearLayout row;
        /** 文本或宽度变了就加一，用来判断离屏高度缓存还能不能用。 */
        int gen;
        /** 这一条回复对应的操作日志从全局日志的第几行开始。 */
        int logFrom;
        /** 已经挂在这条回复上的步骤。运行结束后仍留在这里，点开还能看。 */
        final java.util.ArrayList<String> steps = new java.util.ArrayList<>();
        /** 操作日志默认折起来，用户点开才看每一步。 */
        boolean logOpen;
        @Nullable LinearLayout logBox;
        int cachedGen = -1;
        int cachedHeight;
        int cachedWidth;

        Message(String id, String role, String text) {
            this(id, role, text, List.of());
        }

        Message(String id, String role, String text, List<Attach> files) {
            this.id = id;
            this.role = role;
            this.text = text;
            this.files = new ArrayList<>(files);
        }

        /**
         * 只画在对话里的确认（档案、文件参考权限）。不是模型说的话，不能进发给模型的历史，
         * 否则会变成最后一条助手消息，图片也挂不上去。
         */
        boolean uiOnly;
    }

    /** 当前挂在哪个页面上；页面销毁后视图还活着（见 {@link MusePage} 的缓存），期间为 null。 */
    private @Nullable MusePage mPage;

    /** 视图所属的标签页；页面没挂着时也要能拿到（缓存就是按标签页存的），所以记一份。 */
    private final Tab mTab;
    private final ScrollView mScroll;
    private final LinearLayout mList;
    /** 窗口外的消息不挂在树上，用这两块空白撑住总高度，避免整段对话合成一张超大图层。 */
    private final View mTopGap;
    private final View mBotGap;
    private int mWinStart = -1;
    private int mWinEnd = -1;
    private int mTopSpan = -1;
    private int mBotSpan = -1;
    private boolean mWindowPosted;
    private boolean mBatch;
    /** 思考中那一行要不要留在列表末尾。窗口重挂时靠它把这一行加回去。 */
    private boolean mShowTyping;
    private final LinearLayout mEmpty;
    private final TextView mEmptyHint;
    private final TextView mGuestNote;
    private final TextView mStatus;
    private final TextView mError;
    private final TextView mPages;
    private final LinearLayout mSuspend;
    private boolean mSuspended;
    /** 列表末尾的"Agent 正在…"那一行：三个跳动的点 + 可选的说明文字（工具名）。 */
    private final LinearLayout mTyping;
    private final EditText mInput;
    /** 远程推荐选中后贴在文字上面，标明这一段输入基于哪一项。 */
    private final LinearLayout mBasisRow;
    private final TextView mBasisLabel;
    private @Nullable String mBasisPrompt;
    private boolean mBasisHeld;
    /** 输入栏本身。上拉建议贴着它的上沿，把上面的对话盖住。 */
    private final LinearLayout mInputBar;
    private final FrameLayout mAskSheet;
    private final LinearLayout mAskRows;
    /** 输入框上面的三个动作：问 Aero、解释、查一下。只在打字时出现。 */
    private final LinearLayout mComposeChips;
    /**
     * 附件、设备、三个动作和输入框。打字时这一条的底色和搜索建议相同，输入框自己仍是圆角色块。
     */
    private final LinearLayout mComposeDock;
    /** 切换设备时盖住对话的列表。添加远程电脑只放在这里。 */
    private final FrameLayout mDeviceSheet;
    private final LinearLayout mDeviceCard;
    private final LinearLayout mDeviceRows;
    /** 选择模型时盖住对话的列表。 */
    private final FrameLayout mModelSheet;
    private final LinearLayout mModelCard;
    private final LinearLayout mModelRows;
    /** 选附件类型。和设备、模型同一张贴着输入栏的卡片。 */
    private final FrameLayout mAttachSheet;
    private final LinearLayout mAttachCard;
    private final LinearLayout mAttachChoices;
    /** 程序自己改输入框时不要把上拉建议弹出来。 */
    private int mAskQuiet;
    private int mAskGen;
    private boolean mAskListening;
    private AutocompleteController.@Nullable OnSuggestionsReceivedListener mAskListener;
    private List<AutocompleteMatch> mAskMatches = new ArrayList<>();
    private @Nullable Runnable mAskQuery;
    /** 输入栏和上拉层都挂好了。构造过程里的布局不要去碰还没建好的层。 */
    private boolean mAskPlaced;
    /** 搜索建议用地址栏原来的尺寸和颜色。 */
    private @Nullable OmniboxResourceProvider mOmniboxResources;
    private @Nullable OmniboxImageSupplier mOmniboxImages;
    private final ImageView mSend;
    private final ImageView mMic;
    private final ImageView mAttach;
    private final HorizontalScrollView mAttachScroll;
    private final LinearLayout mAttachRow;
    /** 输入栏上面的操作对象：本机浏览器，或 Tailscale 里的某一台机器。 */
    private final HorizontalScrollView mTargetScroll;
    private final LinearLayout mTargetBlock;
    private final LinearLayout mTargetRow;
    private JSONArray mTargetDevices = new JSONArray();
    private boolean mTargetJoined;
    /** 设备列表已经回来。没回来之前不要把已选的电脑清成本机浏览器。 */
    private boolean mTargetsReady;
    /** 命令打过去的那台电脑。空表示还没有被控制的电脑。 */
    private String mControlledHost = "";
    private int mTargetGen;
    private final Set<String> mExpandedRuns = new HashSet<>();
    /** 操作日志挂在对应的那条回复上，不钉在输入栏。 */
    private final Runnable mAgentLogTick = this::paintAgentLog;
    /** 「Agent 操作」旁边的说明。点问号才出现，一会儿自己收起。 */
    private TextView mTargetHint;
    private Runnable mHideTargetHint;
    /** 模型写下的档案行还没写完时先搁住，避免档案标记闪进对话。 */
    private String mArchiveHold = "";
    private final List<LemurXArchive.Proposal> mArchiveOffers = new ArrayList<>();
    /** 这一轮已经问过的事实。用户点过头之前不再问第二遍。 */
    private final java.util.Set<String> mArchiveAsked = new HashSet<>();
    private final List<Attach> mPending = new ArrayList<>();
    private ExecutorService mAttachRead = Executors.newSingleThreadExecutor();
    private int mAttachGen;
    private boolean mAttachReading;
    private @Nullable SpeechRecognizer mRecognizer;
    private boolean mListening;
    /** 这一轮回复还没结束。麦克风先别开，避免一句话插进正在跑的操作。 */
    private boolean mBusy;
    private @Nullable String mTextBeforeVoice;
    private @Nullable String mVoiceDraft;
    private final ImageView mJump;
    private final LinearLayout mSuggestions;
    private @Nullable View mPreview;
    private static @Nullable MuseChatView sPreviewOwner;
    /** 推荐或设备、模型、附件选择正盖着。返回手势先收这一层。 */
    private static @Nullable MuseChatView sSheetOwner;

    private final List<Message> mMessages = new ArrayList<>();
    private @Nullable String mThreadId;
    private boolean mLoading;
    private boolean mLoaded;
    /** 这次 load 先从本机摆上的条数。之后用户新发的不算在里面。 */
    private int mCacheCount;
    private MuseApi.@Nullable Run mRun;
    /** 拦住游客操作后加一，让被取消的那一轮回调不再改界面。 */
    private int mRunGen;
    private @Nullable Message mStreaming;
    /** 打字机：已经画到这条回复的第几个字符。落后于 {@link Message#text} 时逐帧往前赶。 */
    private int mTyped;
    /** 上一帧打字的时间，用来按经过的时间吐字，而不是一帧跳一大截。 */
    private long mTypeAt;
    private boolean mTypePending;
    private boolean mTypeFinished;
    private @Nullable Message mTypeMessage;
    private boolean mStickToBottom = true;
    /** 刚进入对话，滚动监听先别把“贴底”清掉，等滚到最新一条再说。 */
    private boolean mHoldBottom;
    /** 手指正按在列表上。这时不要把滚动位置拽回底部。 */
    private boolean mUserDrag;
    private float mDragY;
    private boolean mImeSuppressed;
    /**
     * 正在选字的那一条。平时气泡不拿焦点，滚动不去子孙里做选区计算；长按发生前一小段才把这一条
     * 放开，系统工具条（复制、全选、分享）才能出来。选区还在时不能把气泡摘下树，否则选区消失。
     */
    private @Nullable TextView mSelecting;
    private int mPressGen;
    private float mArmX;
    private float mArmY;
    /** 长按之后手指还按着：这一端钉住，另一端跟着拖。 */
    private boolean mDragSelecting;
    private int mSelAnchor;
    private boolean mBlankDown;
    private float mBlankX;
    private float mBlankY;
    /** 空会话建议的代数：晚到的画像响应不能盖掉用户已经开始打的那一轮。 */
    private int mSuggestGen;
    private final List<String[]> mGuides = new ArrayList<>();
    private final List<String[]> mHistory = new ArrayList<>();
    private boolean mHistoryOpen;

    MuseChatView(Context context, MusePage page) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        mPage = page;
        mTab = page.tab();
        int pad = MusePage.pagePadding(context);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        // ---- 消息列表
        mScroll = new ScrollView(context);
        mScroll.setFillViewport(true);
        mScroll.setVerticalScrollBarEnabled(false);
        mScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // 滚动时不去子孙里找焦点，否则每条可选文字都会在滑动里做一次焦点计算。
        mScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        // 键盘收起后视口变高，贴底的思考行要跟着落到新的底部，不能停在原来那一截。
        mScroll.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (bottom - top == oldBottom - oldTop) return;
                    pinIfStuck();
                });
        mScroll.setOnTouchListener(
                (v, event) -> {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            mUserDrag = true;
                            mDragY = event.getY();
                            mHoldBottom = false;
                            break;
                        case MotionEvent.ACTION_MOVE:
                            // 手指向下拖是在看上面的历史。一离开底部就取消贴底，后面的布局不要再拽回来。
                            if (event.getY() - mDragY > dp(8)) mStickToBottom = false;
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            mUserDrag = false;
                            break;
                        default:
                            break;
                    }
                    return false;
                });
        mScroll.getViewTreeObserver()
                .addOnScrollChangedListener(
                        () -> {
                            if (mHoldBottom) return;
                            View child = mScroll.getChildAt(0);
                            if (child == null) return;
                            int bottom = child.getHeight() - mScroll.getHeight() - mScroll.getScrollY();
                            if (mUserDrag) {
                                if (bottom > dp(8)) mStickToBottom = false;
                            } else {
                                mStickToBottom = bottom < dp(24);
                            }
                            updateJump();
                            scheduleWindow();
                        });
        FrameLayout listWrap = new FrameLayout(context);
        mList = new LinearLayout(context);
        mList.setOrientation(LinearLayout.VERTICAL);
        mList.setPadding(pad, dp(8), pad, dp(12));
        // 新消息和思考行是布局完才变高的。贴底时按真实高度再补一次，别停在输入栏后面。
        mList.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (bottom - top == oldBottom - oldTop) return;
                    pinIfStuck();
                });
        mTopGap = new View(context);
        mTopGap.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mBotGap = new View(context);
        mBotGap.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        listWrap.addView(mList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 空状态
        mEmpty = new LinearLayout(context);
        mEmpty.setOrientation(LinearLayout.VERTICAL);
        mEmpty.setGravity(Gravity.CENTER);
        mEmpty.setPadding(pad * 2, pad, pad * 2, pad * 2);
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
        mEmpty.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView brand = new TextView(context);
        brand.setText(R.string.lemurx_muse_empty_title);
        brand.setTextColor(MusePage.text(getContext()));
        brand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        brand.setGravity(Gravity.CENTER);
        brand.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brandLp.topMargin = dp(16);
        mEmpty.addView(brand, brandLp);
        TextView hint = new TextView(context);
        mEmptyHint = hint;
        hint.setText(R.string.lemurx_muse_empty_hint);
        hint.setTextColor(MusePage.muted(getContext()));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(8);
        mEmpty.addView(hint, hintLp);
        mSuggestions = new LinearLayout(context);
        mSuggestions.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sugLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sugLp.topMargin = dp(22);
        mEmpty.addView(mSuggestions, sugLp);
        listWrap.addView(mEmpty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mScroll.addView(listWrap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 列表 + 右下角"回到最新"
        FrameLayout scrollWrap = new FrameLayout(context);
        scrollWrap.addView(mScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mJump = new ImageView(context);
        mJump.setImageResource(R.drawable.lemurx_muse_ic_down);
        mJump.setBackground(MusePage.rounded(
                MusePage.night(context) ? MusePage.surface(context) : 0xFFFFFFFF, 20, context));
        mJump.setColorFilter(MusePage.text(context), PorterDuff.Mode.SRC_IN);
        mJump.setElevation(dp(3));
        mJump.setContentDescription(context.getString(R.string.lemurx_muse_jump_latest));
        mJump.setClickable(true);
        mJump.setFocusable(true);
        mJump.setPadding(dp(9), dp(9), dp(9), dp(9));
        mJump.setVisibility(View.GONE);
        mJump.setOnClickListener(
                v -> {
                    LemurXHaptics.tick(v);
                    mStickToBottom = true;
                    scrollToBottom(true);
                });
        FrameLayout.LayoutParams jumpLp = new FrameLayout.LayoutParams(dp(40), dp(40));
        jumpLp.gravity = Gravity.END | Gravity.BOTTOM;
        jumpLp.setMargins(0, 0, pad, dp(12));
        scrollWrap.addView(mJump, jumpLp);
        column.addView(scrollWrap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 状态 / 错误
        // 状态不再是输入栏上方的一行小字，而是跟在最后一条消息后面的"正在输入"：三个跳动的点，
        // 调工具时在点后面补一句"正在搜索…"。
        mTyping = new LinearLayout(context);
        mTyping.setOrientation(LinearLayout.HORIZONTAL);
        mTyping.setGravity(Gravity.CENTER_VERTICAL);
        mTyping.setPadding(dp(2), dp(8), dp(2), dp(8));
        MuseDotsView dots = new MuseDotsView(context, MusePage.muted(getContext()));
        mTyping.addView(dots);
        mStatus = new TextView(context);
        mStatus.setTextColor(MusePage.muted(getContext()));
        mStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.setMarginStart(dp(10));
        mTyping.addView(mStatus, statusLp);
        mError = new TextView(context);
        mError.setTextColor(MusePage.error(context));
        mError.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mError.setPadding(pad + dp(4), 0, pad, dp(6));
        mError.setVisibility(View.GONE);
        column.addView(mError);

        // Agent 在这个标签里开的页面。点一下回到网页，不用去标签管理里翻。
        mPages = new TextView(context);
        mPages.setTextColor(MusePage.text(getContext()));
        mPages.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mPages.setGravity(Gravity.CENTER_VERTICAL);
        mPages.setBackground(MusePage.rounded(MusePage.surface(getContext()), 14, context));
        mPages.setPadding(dp(14), dp(8), dp(14), dp(8));
        mPages.setVisibility(View.GONE);
        mPages.setClickable(true);
        mPages.setFocusable(true);
        mPages.setOnClickListener(
                v -> {
                    LemurXHaptics.tick(v);
                    LemurXConsoleShell.revealWeb();
                });
        LinearLayout.LayoutParams pagesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pagesLp.setMargins(pad, 0, pad, dp(8));
        column.addView(mPages, pagesLp);

        // 切到后台后停在这里。点继续才让 Agent 接着操作这个标签。
        mSuspend = new LinearLayout(context);
        mSuspend.setOrientation(LinearLayout.HORIZONTAL);
        mSuspend.setGravity(Gravity.CENTER_VERTICAL);
        mSuspend.setBackground(MusePage.rounded(MusePage.surface(getContext()), 14, context));
        mSuspend.setPadding(dp(14), dp(8), dp(8), dp(8));
        mSuspend.setVisibility(View.GONE);
        TextView suspended = new TextView(context);
        suspended.setText(R.string.lemurx_agent_suspended);
        suspended.setTextColor(MusePage.text(getContext()));
        suspended.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mSuspend.addView(suspended, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView resume = new TextView(context);
        resume.setText(R.string.lemurx_agent_resume);
        resume.setTextColor(MusePage.onAccent(context));
        resume.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        resume.setTypeface(Typeface.DEFAULT_BOLD);
        resume.setGravity(Gravity.CENTER);
        resume.setBackground(MusePage.rounded(MusePage.accent(context), 14, context));
        resume.setPadding(dp(14), dp(8), dp(14), dp(8));
        resume.setOnClickListener(
                v -> {
                    LemurXHaptics.click(v);
                    resumeSuspended();
                });
        mSuspend.addView(resume);
        LinearLayout.LayoutParams suspendLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        suspendLp.setMargins(pad, 0, pad, dp(8));
        column.addView(mSuspend, suspendLp);

        // 待发送的附件。读完才出现；点名字右边的移除把它丢掉。
        mAttachRow = new LinearLayout(context);
        mAttachRow.setOrientation(LinearLayout.HORIZONTAL);
        mAttachRow.setGravity(Gravity.CENTER_VERTICAL);
        mAttachScroll = new HorizontalScrollView(context);
        mAttachScroll.setHorizontalScrollBarEnabled(false);
        mAttachScroll.addView(mAttachRow);
        mAttachScroll.setVisibility(View.GONE);
        LinearLayout.LayoutParams attachLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        attachLp.setMargins(pad, 0, pad, dp(8));
        // 打字时这一整条（附件、设备、动作、输入框）要和上面的搜索建议连成同一块底色。
        mComposeDock = new LinearLayout(context);
        mComposeDock.setOrientation(LinearLayout.VERTICAL);
        mComposeDock.addView(mAttachScroll, attachLp);

        // 操作对象。标题写明是给 Agent 操作的，避免只看到一个设备名。
        mTargetRow = new LinearLayout(context);
        mTargetRow.setOrientation(LinearLayout.HORIZONTAL);
        mTargetRow.setGravity(Gravity.CENTER_VERTICAL);
        mTargetScroll = new HorizontalScrollView(context);
        mTargetScroll.setHorizontalScrollBarEnabled(false);
        mTargetScroll.addView(mTargetRow);
        LinearLayout targetBlock = new LinearLayout(context);
        targetBlock.setOrientation(LinearLayout.VERTICAL);
        // 先藏着，refreshTargets 画上「本机浏览器」后再显示。没有远程电脑也要留着这一格。
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView targetTitle = new TextView(context);
        targetTitle.setText(R.string.lemurx_agent_operates);
        targetTitle.setTextColor(MusePage.text(context));
        targetTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        targetTitle.setTypeface(Typeface.DEFAULT_BOLD);
        targetTitle.setPadding(dp(4), 0, dp(6), 0);
        titleRow.addView(targetTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView help = new TextView(context);
        help.setText("?");
        help.setGravity(Gravity.CENTER);
        help.setTextColor(MusePage.muted(context));
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        help.setTypeface(Typeface.DEFAULT_BOLD);
        help.setIncludeFontPadding(false);
        help.setContentDescription(context.getString(R.string.lemurx_agent_operates_help));
        GradientDrawable helpBg = new GradientDrawable();
        helpBg.setShape(GradientDrawable.OVAL);
        helpBg.setColor(0x00000000);
        helpBg.setStroke(dp(1), MusePage.muted(context));
        help.setBackground(helpBg);
        int helpSize = dp(15);
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(helpSize, helpSize);
        titleRow.addView(help, helpLp);
        help.setOnClickListener(v -> showTargetHint());
        LinearLayout.LayoutParams titleRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleRowLp.bottomMargin = dp(10);
        titleRow.setVisibility(View.GONE);
        targetBlock.addView(titleRow, titleRowLp);
        mTargetHint = new TextView(context);
        mTargetHint.setText(R.string.lemurx_agent_operates_hint);
        mTargetHint.setTextColor(MusePage.muted(context));
        mTargetHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mTargetHint.setPadding(dp(4), 0, dp(4), dp(6));
        mTargetHint.setVisibility(View.GONE);
        mHideTargetHint = () -> mTargetHint.setVisibility(View.GONE);
        targetBlock.addView(mTargetHint);
        mTargetBlock = targetBlock;
        // 设备钮挪进输入框底排，这一条不再单独占一行。
        targetBlock.setVisibility(View.GONE);
        LinearLayout.LayoutParams targetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        targetLp.setMargins(pad, 0, pad, dp(8));
        mComposeDock.addView(targetBlock, targetLp);
        mComposeChips = new LinearLayout(context);
        mComposeChips.setOrientation(LinearLayout.HORIZONTAL);
        mComposeChips.setGravity(Gravity.CENTER_VERTICAL);
        mComposeChips.setVisibility(View.GONE);
        addComposeChip(context, R.string.lemurx_muse_compose_ask, () -> sendCompose(false, false));
        addComposeChip(context, R.string.lemurx_muse_compose_explain, () -> sendCompose(true, false));
        addComposeChip(context, R.string.lemurx_muse_compose_lookup, () -> sendCompose(false, true));
        LinearLayout.LayoutParams chipsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipsLp.setMargins(pad, 0, pad, dp(8));
        mComposeDock.addView(mComposeChips, chipsLp);
        refreshTargets();

        // ---- 输入栏：上面是文字，下面是设备、模型，以及靠右的附件、语音、发送。
        mInputBar = new LinearLayout(context);
        mInputBar.setOrientation(LinearLayout.VERTICAL);
        mInputBar.setBackground(MusePage.rounded(MusePage.surface(getContext()), 18, context));
        mInputBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        mBasisRow = new LinearLayout(context);
        mBasisRow.setOrientation(LinearLayout.HORIZONTAL);
        mBasisRow.setGravity(Gravity.CENTER_VERTICAL);
        mBasisRow.setVisibility(View.GONE);
        mBasisRow.setPadding(dp(10), dp(6), dp(4), dp(6));
        mBasisRow.setBackground(MusePage.rounded(MusePage.chipBg(context), 12, context));
        mBasisLabel = new TextView(context);
        mBasisLabel.setSingleLine(true);
        mBasisLabel.setEllipsize(TextUtils.TruncateAt.END);
        mBasisLabel.setTextColor(MusePage.chipText(context));
        mBasisLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mBasisLabel.setTypeface(Typeface.DEFAULT_BOLD);
        mBasisRow.addView(mBasisLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView basisClear = new ImageView(context);
        basisClear.setImageResource(R.drawable.lemurx_muse_ic_x);
        basisClear.setColorFilter(MusePage.chipText(context));
        basisClear.setContentDescription(context.getString(R.string.lemurx_muse_clear_input));
        basisClear.setClickable(true);
        basisClear.setFocusable(false);
        basisClear.setFocusableInTouchMode(false);
        basisClear.setPadding(dp(6), dp(4), dp(6), dp(4));
        basisClear.setOnClickListener(v -> {
            LemurXHaptics.tick(v);
            dismissBasis();
        });
        mBasisRow.addView(basisClear, new LinearLayout.LayoutParams(dp(28), dp(24)));
        LinearLayout.LayoutParams basisLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        basisLp.setMargins(dp(8), dp(6), dp(8), 0);
        mInputBar.addView(mBasisRow, basisLp);
        mInput = new EditText(context);
        mInput.setHint(inputHintRes());
        mInput.setHintTextColor(MusePage.muted(getContext()));
        mInput.setTextColor(MusePage.text(getContext()));
        mInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mInput.setBackground(null);
        mInput.setMinLines(1);
        mInput.setMaxLines(3);
        mInput.setGravity(Gravity.TOP | Gravity.START);
        mInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        mInput.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        mInput.setOnEditorActionListener(
                (v, actionId, event) -> {
                    if (actionId == EditorInfo.IME_ACTION_SEND) {
                        send();
                        return true;
                    }
                    return false;
                });
        mInput.setPadding(dp(12), dp(8), dp(12), dp(4));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mInput.setTextCursorDrawable(new ColorDrawable(MusePage.text(context)));
        }
        mInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                paintMic();
                if (mAskQuiet > 0) return;
                if (mBasisHeld && (s == null || s.toString().trim().isEmpty())) {
                    clearBasis();
                }
                scheduleAskSheet();
            }
        });
        mInputBar.addView(mInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout tools = new LinearLayout(context);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(dp(2), dp(2), dp(2), dp(2));
        // 窄屏时芯片组吃剩余宽度并收缩；右侧附件、麦克风、发送固定宽度，不被挤出。
        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        mTargetScroll.setPadding(0, 0, 0, 0);
        chips.addView(mTargetScroll, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        chips.addView(modelChip(context));
        tools.addView(chips, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mAttach = new ImageView(context);
        mAttach.setImageResource(R.drawable.lemurx_muse_ic_paperclip);
        mAttach.setColorFilter(MusePage.text(context), PorterDuff.Mode.SRC_IN);
        mAttach.setContentDescription(context.getString(R.string.lemurx_muse_attach));
        mAttach.setClickable(true);
        // 和发送钮一样不能抢焦点，否则按下时键盘先收、底栏回来，点按落到药丸上。
        mAttach.setFocusable(false);
        mAttach.setFocusableInTouchMode(false);
        mAttach.setPadding(dp(8), dp(8), dp(8), dp(8));
        mAttach.setOnClickListener(v -> onAttachClicked());
        tools.addView(mAttach, new LinearLayout.LayoutParams(dp(36), dp(36)));
        mMic = new ImageView(context);
        mMic.setImageResource(R.drawable.lemurx_muse_ic_mic);
        mMic.setContentDescription(context.getString(R.string.lemurx_muse_voice));
        mMic.setClickable(true);
        // 和发送钮一样不能抢焦点，否则按下时键盘先收、底栏回来，点按落到药丸上。
        mMic.setFocusable(false);
        mMic.setFocusableInTouchMode(false);
        mMic.setPadding(dp(8), dp(8), dp(8), dp(8));
        mMic.setOnClickListener(v -> onMicClicked());
        paintMic();
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        micLp.setMargins(0, 0, dp(2), 0);
        tools.addView(mMic, micLp);
        mSend = new ImageView(context);
        mSend.setImageResource(R.drawable.lemurx_muse_ic_send);
        mSend.setBackground(MusePage.rounded(MusePage.accent(context), 20, context));
        mSend.setColorFilter(MusePage.onAccent(context), PorterDuff.Mode.SRC_IN);
        mSend.setContentDescription(context.getString(R.string.lemurx_muse_send));
        mSend.setClickable(true);
        // 不能抢焦点：按下时输入框一失焦，键盘收起、底栏立刻回来，点按就落到药丸上。
        // 消息接受这次点击之后，再主动把键盘收起来。
        mSend.setFocusable(false);
        mSend.setFocusableInTouchMode(false);
        mSend.setPadding(dp(8), dp(8), dp(8), dp(8));
        mSend.setOnClickListener(v -> onSendClicked());
        tools.addView(mSend, new LinearLayout.LayoutParams(dp(36), dp(36)));
        mInputBar.addView(tools, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mGuestNote = new TextView(context);
        mGuestNote.setText(R.string.lemurx_muse_guest_agent);
        mGuestNote.setTextColor(MusePage.accent(context));
        mGuestNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mGuestNote.setGravity(Gravity.CENTER);
        mGuestNote.setPadding(dp(12), dp(8), dp(12), dp(4));
        mGuestNote.setClickable(true);
        mGuestNote.setFocusable(true);
        mGuestNote.setOnClickListener(
                v -> {
                    LemurXHaptics.tick(v);
                    MusePage.requireAccount();
                });
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.setMargins(pad, 0, pad, 0);
        mComposeDock.addView(mGuestNote, noteLp);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.setMargins(pad, 0, pad, dp(10));
        mComposeDock.addView(mInputBar, barLp);
        column.addView(mComposeDock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 输入时盖住对话。搜索建议和下面的输入区用同一块底色。
        mAskSheet = new FrameLayout(context);
        mAskSheet.setVisibility(View.GONE);
        mAskSheet.setClickable(true);
        mAskSheet.setFocusable(false);
        mAskSheet.setFocusableInTouchMode(false);
        ScrollView askScroll = new ScrollView(context);
        askScroll.setFillViewport(true);
        askScroll.setClickable(true);
        askScroll.setFocusable(false);
        askScroll.setFocusableInTouchMode(false);
        askScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        mAskRows = new LinearLayout(context);
        mAskRows.setOrientation(LinearLayout.VERTICAL);
        mAskRows.setClickable(true);
        mAskRows.setPadding(dp(16), dp(12), dp(16), dp(16));
        askScroll.addView(mAskRows, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mAskSheet.addView(askScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(mAskSheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mDeviceSheet = newPickerSheet(context, v -> hideDeviceSheet());
        ScrollView deviceScroll = newPickerScroll(context);
        mDeviceRows = newPickerRows(context);
        deviceScroll.addView(mDeviceRows, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mDeviceCard = pickerSheetCard(mDeviceSheet, deviceScroll, R.string.lemurx_muse_target_sheet);
        addView(mDeviceSheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mModelSheet = newPickerSheet(context, v -> hideModelSheet());
        ScrollView modelScroll = newPickerScroll(context);
        mModelRows = newPickerRows(context);
        modelScroll.addView(mModelRows, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mModelCard = pickerSheetCard(mModelSheet, modelScroll, R.string.lemurx_muse_model_sheet);
        addView(mModelSheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mAttachSheet = newPickerSheet(context, v -> hideAttachSheet());
        ScrollView attachScroll = newPickerScroll(context);
        mAttachChoices = newPickerRows(context);
        attachScroll.addView(mAttachChoices, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mAttachCard = pickerSheetCard(mAttachSheet, attachScroll, R.string.lemurx_muse_attach_pick);
        addView(mAttachSheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 软键盘弹出时把底部药丸收起来，输入栏贴着键盘；收起后再放回去。
        setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    updateImeSuppression();
                    return insets;
                });
        mInput.setOnFocusChangeListener(
                (v, hasFocus) -> {
                    updateImeSuppression();
                    if (hasFocus) scheduleAskSheet();
                    else hideAskSheet();
                });
        paintAccountMode();
        mAskPlaced = true;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        positionAskSheet();
    }

    /** 上拉建议停在附件、操作对象和输入栏之上，把对话盖住。 */
    private void positionAskSheet() {
        if (!mAskPlaced) return;
        positionCover(mAskSheet);
        positionCover(mDeviceSheet);
        positionCover(mModelSheet);
        positionCover(mAttachSheet);
    }

    /** 上拉层停在仍露着的输入栏、快捷按钮或设备按钮之上。 */
    private void positionCover(FrameLayout sheet) {
        if (sheet.getVisibility() != View.VISIBLE) return;
        int top = viewTop(mInputBar);
        if (mAttachScroll.getVisibility() == View.VISIBLE) {
            top = Math.min(top, viewTop(mAttachScroll));
        }
        if (mComposeChips.getVisibility() == View.VISIBLE) {
            top = Math.min(top, viewTop(mComposeChips));
        }
        if (mTargetBlock.getVisibility() == View.VISIBLE) {
            top = Math.min(top, viewTop(mTargetBlock));
        }
        int inset = Math.max(0, getHeight() - top);
        ViewGroup.LayoutParams raw = sheet.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        if (lp.bottomMargin == inset) return;
        lp.bottomMargin = inset;
        sheet.setLayoutParams(lp);
    }

    private int viewTop(View view) {
        int y = 0;
        View cursor = view;
        while (cursor != null && cursor != this) {
            y += cursor.getTop();
            if (!(cursor.getParent() instanceof View)) break;
            cursor = (View) cursor.getParent();
        }
        return y;
    }

    private void scheduleAskSheet() {
        if (mAskQuery != null) mInput.removeCallbacks(mAskQuery);
        mAskQuery = () -> {
            mAskQuery = null;
            String text = mInput.getText() == null ? "" : mInput.getText().toString().trim();
            // 远程电脑一点进输入框就给出常用操作，不必先打字。本机浏览器仍要有内容才出搜索建议。
            if (mBasisHeld || !mInput.hasFocus() || (text.isEmpty() && !onRemoteComputer())) {
                hideAskSheet();
                return;
            }
            showAskSheet(text);
        };
        mInput.postDelayed(mAskQuery, 50);
    }

    private void showAskSheet(String text) {
        boolean fresh = mAskSheet.getVisibility() != View.VISIBLE;
        if (mPage != null) mPage.setComposeHeaderHidden(true);
        hideDeviceSheet();
        hideModelSheet();
        hideAttachSheet();
        mTargetBlock.setVisibility(View.GONE);
        mComposeChips.setVisibility(onRemoteComputer() ? View.GONE : View.VISIBLE);
        paintComposeSurface(true);
        paintAskSheet(text);
        mAskSheet.setVisibility(View.VISIBLE);
        updateJump();
        if (fresh) {
            mAskSheet.setTranslationY(dp(40));
            mAskSheet.animate().translationY(0f).setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .start();
        }
        if (onRemoteComputer()) stopAskQuery();
        else startAskQuery(text);
        noteComposeSheets();
    }

    private void hideAskSheet() {
        if (!mAskPlaced) return;
        if (mAskQuery != null) {
            mInput.removeCallbacks(mAskQuery);
            mAskQuery = null;
        }
        stopAskQuery();
        if (mAskSheet.getVisibility() == View.GONE) return;
        mAskSheet.animate().cancel();
        mAskSheet.setTranslationY(0f);
        mAskSheet.setVisibility(View.GONE);
        mAskRows.removeAllViews();
        mComposeChips.setVisibility(View.GONE);
        paintComposeSurface(false);
        mTargetBlock.setVisibility(View.GONE);
        if (mPage != null) mPage.setComposeHeaderHidden(false);
        updateJump();
        noteComposeSheets();
    }

    boolean isComposing() {
        return mAskPlaced && mAskSheet.getVisibility() == View.VISIBLE;
    }

    /** 搜索建议那一块的底色。打字时按钮所在的底栏跟它用同一个颜色，输入框色块单独留着。 */
    private int composeSurfaceColor() {
        return OmniboxResourceProvider.getStandardSuggestionBackgroundColor(
                getContext(), BrandedColorScheme.APP_DEFAULT);
    }

    /**
     * 打字时建议列表和三个动作所在的底栏铺成同一块颜色。输入框保持自己的圆角色块，
     * 收起后底栏不再单独铺色。
     */
    private void paintComposeSurface(boolean composing) {
        if (!composing) {
            mComposeDock.setBackground(null);
            return;
        }
        int color = composeSurfaceColor();
        mComposeDock.setBackgroundColor(color);
        mAskSheet.setBackgroundColor(color);
        for (int i = 0; i < mComposeChips.getChildCount(); i++) {
            Drawable background = mComposeChips.getChildAt(i).getBackground();
            if (background instanceof GradientDrawable) {
                ((GradientDrawable) background).setColor(color);
            }
        }
    }

    /** 三个动作和底栏同色，只留一圈细边，避免再叠一块不一样的底。 */
    private Drawable composeChipBackground(Context context) {
        GradientDrawable background = MusePage.rounded(composeSurfaceColor(), 16, context);
        background.setStroke(dp(1), MusePage.outline(context));
        return background;
    }

    private void paintAskSheet(String text) {
        Context context = getContext();
        mAskRows.removeAllViews();
        if (onRemoteComputer()) {
            paintComputerActions(context, text);
            return;
        }
        String engine = searchEngineName();
        int shown = 0;
        for (AutocompleteMatch match : mAskMatches) {
            if (shown >= 6) break;
            if (match == null || !addOmniboxMatch(context, match, engine)) continue;
            shown++;
        }
        if (shown == 0) {
            addOmniboxRow(context, null, true, null, text, engine, () -> openWeb(text));
        }
    }

    /** 远程电脑上工程师会做的操作。没打字时全部列出；打了字就只留对得上的，对不上就执行原句。 */
    private void paintComputerActions(Context context, String text) {
        String query = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT);
        int[][] actions = {
            {R.string.lemurx_muse_pc_git, R.string.lemurx_muse_pc_git_sub,
                    R.string.lemurx_muse_pc_git_prompt, R.drawable.lemurx_muse_ic_code},
            {R.string.lemurx_muse_pc_test, R.string.lemurx_muse_pc_test_sub,
                    R.string.lemurx_muse_pc_test_prompt, R.drawable.lemurx_muse_ic_refresh},
            {R.string.lemurx_muse_pc_build, R.string.lemurx_muse_pc_build_sub,
                    R.string.lemurx_muse_pc_build_prompt, R.drawable.lemurx_muse_ic_cpu},
            {R.string.lemurx_muse_pc_logs, R.string.lemurx_muse_pc_logs_sub,
                    R.string.lemurx_muse_pc_logs_prompt, R.drawable.lemurx_muse_ic_file_text},
            {R.string.lemurx_muse_pc_ports, R.string.lemurx_muse_pc_ports_sub,
                    R.string.lemurx_muse_pc_ports_prompt, R.drawable.lemurx_muse_ic_server},
            {R.string.lemurx_muse_pc_health, R.string.lemurx_muse_pc_health_sub,
                    R.string.lemurx_muse_pc_health_prompt, R.drawable.lemurx_muse_ic_info},
        };
        int shown = 0;
        for (int[] action : actions) {
            String title = context.getString(action[0]);
            String sub = context.getString(action[1]);
            String prompt = context.getString(action[2]);
            if (!query.isEmpty()
                    && !title.toLowerCase(java.util.Locale.ROOT).contains(query)
                    && !sub.toLowerCase(java.util.Locale.ROOT).contains(query)
                    && !prompt.toLowerCase(java.util.Locale.ROOT).contains(query)) {
                continue;
            }
            int icon = action[3];
            addComputerRow(context, icon, title, sub, () -> applyComputerBasis(title, prompt));
            shown++;
        }
        if (shown == 0 && !query.isEmpty()) {
            addComputerRow(context, R.drawable.lemurx_muse_ic_code, text,
                    context.getString(R.string.lemurx_muse_computer_do),
                    () -> applyComputerBasis(
                            context.getString(R.string.lemurx_muse_computer_do), text));
        }
    }

    /** 当前默认搜索引擎的短名，比如 Google。还没加载好时返回空。 */
    private String searchEngineName() {
        try {
            Profile profile = mTab.getProfile();
            if (profile == null) return "";
            TemplateUrlService service = TemplateUrlServiceFactory.getForProfile(profile);
            if (service == null || !service.isLoaded()) return "";
            TemplateUrl engine = service.getDefaultSearchEngineTemplateUrl();
            if (engine == null) return "";
            String name = engine.getShortName();
            return name == null ? "" : name.trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /**
     * 一条地址栏建议。搜索是放大镜加查询，默认搜索那条的第二行是搜索引擎名；
     * 网址是地球图标、标题和第二行地址。
     */
    private boolean addOmniboxMatch(Context context, AutocompleteMatch match, String engine) {
        String display = match.getDisplayText() == null ? "" : match.getDisplayText().trim();
        String description = match.getDescription() == null ? "" : match.getDescription().trim();
        boolean search = match.isSearchSuggestion();
        GURL url = match.getUrl();
        String spec = url == null || !url.isValid() ? "" : url.getSpec();
        if (search) {
            if (display.isEmpty()) return false;
            String second = !description.isEmpty()
                    ? description
                    : (match.isWhatYouTyped() ? engine : "");
            String open = spec.isEmpty() ? display : spec;
            addOmniboxRow(context, match, true, url, display, second, () -> openWeb(open));
            return true;
        }
        if (spec.isEmpty() && display.isEmpty()) return false;
        String title = !description.isEmpty() ? description : display;
        String address = !display.isEmpty() ? prettyUrl(display) : prettyUrl(spec);
        if (title.equals(address)) address = "";
        String open = spec.isEmpty() ? display : spec;
        addOmniboxRow(context, match, false, url, title, address, () -> openWeb(open));
        return true;
    }

    private void addOmniboxRow(
            Context context,
            @Nullable AutocompleteMatch match,
            boolean search,
            @Nullable GURL pageUrl,
            String line1,
            String line2,
            Runnable action) {
        OmniboxResourceProvider resources = omniboxResources();
        int layout = org.chromium.chrome.browser.omnibox.R.layout.omnibox_basic_suggestion;
        BaseSuggestionView<View> row = new BaseSuggestionView<>(context, layout);
        row.setSuggestionDimensions(
                resources.getSuggestionDecorationIconSizeWidth(),
                resources.getSuggestionContentHeight(),
                resources.getSuggestionCompactContentHeight(),
                resources.getSuggestionContentVerticalPadding());
        row.setFocusable(false);
        row.setFocusableInTouchMode(false);
        fitSuggestionIcon(row, false, false);
        int icon = fallbackSuggestionIcon(match, search);
        Drawable drawable = AppCompatResources.getDrawable(context, icon);
        row.decorationIcon.setImageDrawable(drawable);
        ImageViewCompat.setImageTintList(
                row.decorationIcon, ChromeColors.getSecondaryIconTint(context, false));
        loadSuggestionImage(row, match, search, pageUrl);
        TextView first = row.contentView.findViewById(
                org.chromium.chrome.browser.omnibox.R.id.line_1);
        TextView second = row.contentView.findViewById(
                org.chromium.chrome.browser.omnibox.R.id.line_2);
        first.setText(line1);
        first.setTextColor(OmniboxResourceProvider.getSuggestionPrimaryTextColor(
                context, BrandedColorScheme.APP_DEFAULT));
        if (TextUtils.isEmpty(line2)) {
            second.setVisibility(View.GONE);
        } else {
            second.setText(line2);
            second.setVisibility(View.VISIBLE);
            second.setTextColor(search
                    ? OmniboxResourceProvider.getSuggestionSecondaryTextColor(
                            context, BrandedColorScheme.APP_DEFAULT)
                    : OmniboxResourceProvider.getSuggestionUrlTextColor(
                            context, BrandedColorScheme.APP_DEFAULT));
        }
        row.setOnClickListener(
                v -> {
                    LemurXHaptics.tick(v);
                    action.run();
                });
        mAskRows.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.applySideSpacing(false, resources.getSideSpacing());
    }

    /** 一条电脑操作。样子跟搜索建议同一行，图标换成这件事自己的。 */
    private void addComputerRow(
            Context context, int icon, String line1, String line2, Runnable action) {
        addOmniboxRow(context, null, true, null, line1, line2, action);
        View last = mAskRows.getChildAt(mAskRows.getChildCount() - 1);
        if (!(last instanceof BaseSuggestionView)) return;
        @SuppressWarnings("unchecked")
        BaseSuggestionView<View> row = (BaseSuggestionView<View>) last;
        row.decorationIcon.setImageResource(icon);
        ImageViewCompat.setImageTintList(
                row.decorationIcon, ChromeColors.getSecondaryIconTint(context, false));
    }

    private OmniboxResourceProvider omniboxResources() {
        if (mOmniboxResources == null) {
            mOmniboxResources = new OmniboxResourceProvider(
                    getContext(), BrandedColorScheme.APP_DEFAULT);
        }
        return mOmniboxResources;
    }

    private OmniboxImageSupplier omniboxImages() {
        if (mOmniboxImages == null) {
            mOmniboxImages = new OmniboxImageSupplier(getContext());
            mOmniboxImages.onNativeInitialized();
            Profile profile = mTab.getProfile();
            if (profile != null) mOmniboxImages.setProfile(profile);
        }
        return mOmniboxImages;
    }

    /** 还没有方形图时用的矢量图标。历史、趋势和网址各有自己的符号。 */
    private int fallbackSuggestionIcon(@Nullable AutocompleteMatch match, boolean search) {
        if (!search || match == null) {
            return search
                    ? org.chromium.chrome.browser.omnibox.R.drawable.ic_suggestion_magnifier
                    : org.chromium.chrome.browser.omnibox.R.drawable.ic_globe_24dp;
        }
        int type = match.getType();
        if (type == OmniboxSuggestionType.SEARCH_HISTORY
                || type == OmniboxSuggestionType.SEARCH_SUGGEST_PERSONALIZED) {
            return org.chromium.chrome.browser.omnibox.R.drawable.ic_history_24dp;
        }
        switch (match.getIconType()) {
            case SuggestTemplateInfo.IconType.HISTORY_VALUE:
                return org.chromium.chrome.browser.omnibox.R.drawable.ic_history_24dp;
            case SuggestTemplateInfo.IconType.TRENDING_VALUE:
                return org.chromium.chrome.browser.omnibox.R.drawable.trending_up_black_24dp;
            case SuggestTemplateInfo.IconType.SEARCH_LOOP_WITH_SPARKLE_VALUE:
                return org.chromium.chrome.browser.omnibox.R.drawable.search_spark_black_24dp;
            case SuggestTemplateInfo.IconType.GLOBE_WITH_SEARCH_LOOP_VALUE:
                return org.chromium.chrome.browser.omnibox.R.drawable.travel_explore_24dp;
            case SuggestTemplateInfo.IconType.FAVICON_VALUE:
                return org.chromium.chrome.browser.omnibox.R.drawable.ic_globe_24dp;
            default:
                return org.chromium.chrome.browser.omnibox.R.drawable.ic_suggestion_magnifier;
        }
    }

    /**
     * 图标槽和地址栏一致：小图 24dp、配图 36dp，圆角不超过边长的四分之一，所以是圆角方形不是圆。
     * 配图铺满这个方槽再裁角；矢量图标只居中，不裁成圆。
     */
    private void fitSuggestionIcon(BaseSuggestionView<View> row, boolean large, boolean crop) {
        int edge = large
                ? omniboxResources().getEdgeSizeLargeIcon()
                : omniboxResources().getEdgeSize();
        int radius = large
                ? omniboxResources().getLargeIconRoundingRadius()
                : omniboxResources().getSmallIconRoundingRadius();
        if (large) row.setUseLargeDecorationIcon(true);
        ImageView icon = row.decorationIcon;
        ViewGroup.LayoutParams lp = icon.getLayoutParams();
        lp.width = edge;
        lp.height = edge;
        icon.setLayoutParams(lp);
        icon.setScaleType(crop ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.CENTER_INSIDE);
        icon.setClipToOutline(crop);
        row.decorationIconOutline.setRadius(Math.min(radius, edge / 4));
        icon.invalidateOutline();
    }

    /** 搜索实体用方形配图，网址用网站图标。拿到图之前先留着矢量图标。 */
    private void loadSuggestionImage(
            BaseSuggestionView<View> row,
            @Nullable AutocompleteMatch match,
            boolean search,
            @Nullable GURL pageUrl) {
        try {
            if (search && match != null) {
                GURL image = match.getImageUrl();
                if (image != null && image.isValid() && !image.isEmpty()) {
                    omniboxImages().fetchImage(image, drawable ->
                            applySuggestionImage(row, drawable, true));
                }
                return;
            }
            if (pageUrl != null && pageUrl.isValid() && !pageUrl.isEmpty()) {
                omniboxImages().fetchFavicon(pageUrl, drawable ->
                        applySuggestionImage(row, drawable, false));
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void applySuggestionImage(
            BaseSuggestionView<View> row, @Nullable Drawable drawable, boolean large) {
        if (drawable == null || row.getParent() == null) return;
        Runnable paint = () -> {
            if (row.getParent() == null) return;
            row.decorationIcon.setImageDrawable(drawable);
            ImageViewCompat.setImageTintList(row.decorationIcon, null);
            row.decorationIcon.setColorFilter(null);
            fitSuggestionIcon(row, large, true);
        };
        if (ThreadUtils.runningOnUiThread()) paint.run();
        else row.post(paint);
    }

    private void sendAsk(String prompt) {
        if (TextUtils.isEmpty(prompt)) return;
        mAskQuiet++;
        mInput.setText(prompt);
        mInput.setSelection(prompt.length());
        mAskQuiet--;
        hideAskSheet();
        send();
    }

    /** 选中一项远程操作：把对应指令写进输入框，并标出这段文字基于它。先不发送。 */
    private void applyComputerBasis(String title, String prompt) {
        if (TextUtils.isEmpty(prompt)) return;
        mBasisPrompt = prompt;
        mBasisHeld = true;
        mBasisLabel.setText(getContext().getString(R.string.lemurx_muse_computer_basis, title));
        mBasisRow.setVisibility(View.VISIBLE);
        mAskQuiet++;
        mInput.setText(prompt);
        mInput.setSelection(prompt.length());
        mAskQuiet--;
        hideAskSheet();
        if (!mInput.hasFocus()) mInput.requestFocus();
    }

    private void clearBasis() {
        mBasisHeld = false;
        mBasisPrompt = null;
        mBasisRow.setVisibility(View.GONE);
    }

    /** 去掉“基于哪一项”。文字若仍是那条指令，一并清掉，推荐可以再出来。 */
    private void dismissBasis() {
        String prompt = mBasisPrompt;
        clearBasis();
        if (prompt != null && mInput.getText() != null && prompt.contentEquals(mInput.getText())) {
            mAskQuiet++;
            mInput.setText("");
            mAskQuiet--;
            scheduleAskSheet();
        }
    }

    /** 推荐列表，或设备、模型、附件选择，有一层正开着。 */
    static boolean sheetsOpen() {
        MuseChatView owner = sSheetOwner;
        return owner != null && owner.composeSheetOpen();
    }

    /** 返回手势：先收选择框，再收推荐。没有这些层时不拦截。 */
    static boolean consumeSheetBack() {
        MuseChatView owner = sSheetOwner;
        if (owner == null || !owner.composeSheetOpen()) return false;
        return owner.closeTopComposeSheet();
    }

    private boolean composeSheetOpen() {
        if (!mAskPlaced) return false;
        return mAttachSheet.getVisibility() == View.VISIBLE
                || mModelSheet.getVisibility() == View.VISIBLE
                || mDeviceSheet.getVisibility() == View.VISIBLE
                || mAskSheet.getVisibility() == View.VISIBLE;
    }

    private boolean closeTopComposeSheet() {
        if (mAttachSheet.getVisibility() == View.VISIBLE) {
            hideAttachSheet();
            return true;
        }
        if (mModelSheet.getVisibility() == View.VISIBLE) {
            hideModelSheet();
            return true;
        }
        if (mDeviceSheet.getVisibility() == View.VISIBLE) {
            hideDeviceSheet();
            return true;
        }
        if (mAskSheet.getVisibility() == View.VISIBLE) {
            hideAskSheet();
            return true;
        }
        return false;
    }

    private void noteComposeSheets() {
        if (!mAskPlaced) return;
        if (composeSheetOpen()) sSheetOwner = this;
        else if (sSheetOwner == this) sSheetOwner = null;
        BackPressManager.lemurXConsoleStateChanged();
    }

    private void openWeb(String target) {
        hideKeyboard();
        mAskQuiet++;
        mInput.setText("");
        mAskQuiet--;
        hideAskSheet();
        LemurXConsoleShell.openTyped(mTab, target);
    }

    private void startAskQuery(String text) {
        try {
            Profile profile = mTab.getProfile();
            if (profile == null) return;
            AutocompleteController controller = AutocompleteController.getForProfile(profile);
            if (controller == null) return;
            if (!mAskListening) {
                final int gen = ++mAskGen;
                mAskListener = (result, isFinal) -> deliverAskMatches(gen, result);
                controller.addOnSuggestionsReceivedListener(mAskListener);
                controller.resetSession();
                mAskListening = true;
            }
            AutocompleteInput input = new AutocompleteInput(OmniboxFocusReason.OMNIBOX_TAP);
            input.setUserText(text);
            input.setPageClassification(PageClassification.OTHER);
            GURL page = mTab.getUrl();
            if (page != null) input.setPageUrl(page);
            int cursor = mInput.getSelectionEnd();
            if (cursor < 0) cursor = text.length();
            controller.start(mTab.getWebContents(), input, cursor, true);
        } catch (RuntimeException ignored) {
        }
    }

    private void deliverAskMatches(int gen, @Nullable AutocompleteResult result) {
        if (!ThreadUtils.runningOnUiThread()) {
            mInput.post(() -> deliverAskMatches(gen, result));
            return;
        }
        if (gen != mAskGen || mAskSheet.getVisibility() != View.VISIBLE) return;
        if (onRemoteComputer()) return;
        mAskMatches = result == null
                ? new ArrayList<>()
                : new ArrayList<>(result.getSuggestionsList());
        String text = mInput.getText() == null ? "" : mInput.getText().toString().trim();
        if (!text.isEmpty()) paintAskSheet(text);
    }

    private void stopAskQuery() {
        if (!mAskListening && mAskListener == null) {
            mAskMatches = new ArrayList<>();
            return;
        }
        mAskGen++;
        mAskListening = false;
        mAskMatches = new ArrayList<>();
        AutocompleteController.OnSuggestionsReceivedListener listener = mAskListener;
        mAskListener = null;
        try {
            Profile profile = mTab.getProfile();
            AutocompleteController controller =
                    profile == null ? null : AutocompleteController.getForProfile(profile);
            if (controller != null) {
                if (listener != null) controller.removeOnSuggestionsReceivedListener(listener);
                controller.stop(AutocompleteStopReason.INTERACTION);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static String prettyUrl(String spec) {
        if (spec.startsWith("https://")) return spec.substring("https://".length());
        if (spec.startsWith("http://")) return spec.substring("http://".length());
        return spec;
    }

    private int dp(int value) {
        return MusePage.dp(getContext(), value);
    }

    private void updateImeSuppression() {
        boolean ime = false;
        try {
            WindowInsets insets = getRootWindowInsets();
            if (insets != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ime = insets.isVisible(WindowInsets.Type.ime()) && mInput.hasFocus();
            } else {
                ime = mInput.hasFocus();
            }
        } catch (Exception e) {
            ime = false;
        }
        if (ime != mImeSuppressed) {
            mImeSuppressed = ime;
            LemurXConsoleShell.setNavSuppressed(this, ime);
        }
    }

    /**
     * 点在消息和按钮以外的空白上，收起键盘。文字和输入框不算空白。
     * 先把事件交给被点的那一行，再收键盘。否则收键盘会清掉输入焦点，推荐列表先被拆掉，点击就丢了。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mBlankDown = isBlank(ev);
            mBlankX = ev.getX();
            mBlankY = ev.getY();
        }
        boolean handled = super.dispatchTouchEvent(ev);
        if (action == MotionEvent.ACTION_UP && mBlankDown && mSelecting == null) {
            float dx = ev.getX() - mBlankX;
            float dy = ev.getY() - mBlankY;
            int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            if (dx * dx + dy * dy <= (float) slop * slop) hideKeyboard();
        }
        return handled;
    }

    private boolean isBlank(MotionEvent ev) {
        View hit = hitView(this, ev.getX(), ev.getY());
        if (hit == null || hit == this || hit == mScroll || hit == mList || hit == mEmpty) return true;
        if (hit == mInput || hit instanceof EditText) return false;
        for (View cursor = hit; cursor != null && cursor != this; cursor = parentOf(cursor)) {
            if (cursor == mAskSheet || cursor instanceof ScrollView) continue;
            if (cursor.isClickable() || cursor.isLongClickable()) return false;
        }
        return true;
    }

    private static @Nullable View parentOf(View view) {
        if (!(view.getParent() instanceof View)) return null;
        return (View) view.getParent();
    }

    private static @Nullable View hitView(View view, float x, float y) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (x < 0 || y < 0 || x >= view.getWidth() || y >= view.getHeight()) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                View hit = hitView(child, x - child.getLeft() + child.getScrollX(),
                        y - child.getTop() + child.getScrollY());
                if (hit != null) return hit;
            }
        }
        return view;
    }

    /** 发送已经落到按钮上之后再收键盘。按钮本身不拿焦点，避免按下的瞬间点按滑到导航栏。 */
    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(mInput.getWindowToken(), 0);
        }
        mInput.clearFocus();
        updateImeSuppression();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        LemurXDeviceBridge.addActionListener(mAgentLogTick);
        paintAgentLog();
        refreshAgentPages();
    }

    @Override
    protected void onDetachedFromWindow() {
        hideAskSheet();
        if (mOmniboxResources != null) {
            mOmniboxResources.destroy();
            mOmniboxResources = null;
        }
        if (mOmniboxImages != null) {
            mOmniboxImages.destroy();
            mOmniboxImages = null;
        }
        LemurXDeviceBridge.removeActionListener(mAgentLogTick);
        releaseVoice();
        if (sSheetOwner == this) {
            sSheetOwner = null;
            BackPressManager.lemurXConsoleStateChanged();
        }
        if (mImeSuppressed) {
            mImeSuppressed = false;
            LemurXConsoleShell.setNavSuppressed(this, false);
        }
        super.onDetachedFromWindow();
    }

    @Override
    public void setVisibility(int visibility) {
        boolean opening = visibility == View.VISIBLE && getVisibility() != View.VISIBLE;
        super.setVisibility(visibility);
        if (visibility != View.VISIBLE && mMic != null) {
            hideAskSheet();
            hideDeviceSheet();
            hideModelSheet();
            hideAttachSheet();
            endVoice(true);
            if (mImeSuppressed) {
                mImeSuppressed = false;
                LemurXConsoleShell.setNavSuppressed(this, false);
            }
        }
        if (opening && mTargetScroll != null) refreshTargets();
    }

    // ---------------------------------------------------------------- 页面挂载

    /** 这个标签页重建了 MusePage：视图挂回去。 */
    void attachPage(MusePage page) {
        mPage = page;
    }

    /** 页面销毁了，视图留着（进行中的回复继续收，草稿和滚动位置都在）。 */
    void detachPage(MusePage page) {
        if (mPage == page) mPage = null;
    }

    // ---------------------------------------------------------------- 会话

    /** 第一条用户消息（截断），作为标签页标题；没有就 null。 */
    @Nullable String title() {
        for (Message m : mMessages) {
            if ("user".equals(m.role) && !TextUtils.isEmpty(m.text)) {
                String t = m.text.trim().replace('\n', ' ');
                return t.length() > TITLE_MAX ? t.substring(0, TITLE_MAX) + "…" : t;
            }
        }
        return null;
    }

    @Nullable String threadId() {
        return mThreadId;
    }

    boolean running() {
        return mRun != null;
    }

    /** 切到某个会话：同一个就停在最新一条；换了会话时取消进行中的回复并重新拉历史。 */
    void load(String threadId) {
        if (threadId.equals(mThreadId)) {
            revealLatest();
            return;
        }
        stop();
        mThreadId = threadId;
        mMessages.clear();
        mList.removeAllViews();
        mWinStart = -1;
        mWinEnd = -1;
        mTopSpan = -1;
        mBotSpan = -1;
        mLoaded = false;
        showError(null);
        boolean hadCache = paintCached(threadId);
        mLoading = !hadCache;
        updateEmpty();
        if (hadCache) {
            mLoaded = true;
            revealLatest();
        }
        final String id = threadId;
        MuseApi.get()
                .get(
                        "/api/conversation?id=" + id,
                        (value, error) -> {
                            if (!id.equals(mThreadId)) return;
                            mLoading = false;
                            if (error != null) {
                                showError(error);
                                updateEmpty();
                                return;
                            }
                            mLoaded = true;
                            JSONArray messages =
                                    value instanceof JSONObject
                                            ? ((JSONObject) value).optJSONArray("messages")
                                            : null;
                            if (messages == null) messages = new JSONArray();
                            int typedFrom = Math.min(mCacheCount, mMessages.size());
                            List<Message> typed = new ArrayList<>(mMessages.subList(typedFrom, mMessages.size()));
                            // 本地已经摆上同一份时不要清掉重画，避免打开时先空再被盖上一堆。
                            if (typed.isEmpty() && sameAsShowing(messages)) {
                                mLoaded = true;
                                updateEmpty();
                                writeCache(messages);
                                return;
                            }
                            mMessages.clear();
                            mList.removeAllViews();
                            mWinStart = -1;
                            mWinEnd = -1;
                            mTopSpan = -1;
                            mBotSpan = -1;
                            mBatch = true;
                            appendStored(messages, id);
                            for (Message m : typed) append(m);
                            mBatch = false;
                            mCacheCount = mMessages.size() - typed.size();
                            scheduleWindow();
                            rememberTitle();
                            notifyTitleChanged();
                            revealLatest();
                            writeCache(messages);
                        });
    }

    /** 进入对话时停在最新一条。窗口重挂时也按贴底来，避免把滚动位置写回顶部。 */
    void revealLatest() {
        mStickToBottom = true;
        mHoldBottom = true;
        scheduleWindow();
        scrollToBottom(false);
    }

    private void notifyTitleChanged() {
        MusePage page = mPage;
        if (page != null) page.notifyTitleChanged();
    }

    private void openInNewTab(String url) {
        MusePage page = mPage;
        if (page != null) page.openInNewTab(url);
    }

    private void rememberTitle() {
        String title = title();
        if (mThreadId == null) return;
        if (title != null) {
            sTitles.put(mThreadId, title);
        } else {
            sTitles.remove(mThreadId);
        }
    }

    private void updateEmpty() {
        paintAccountMode();
        boolean empty = mMessages.isEmpty() && !mLoading;
        if (empty) {
            buildSuggestions();
        } else {
            mSuggestGen++;
            mSuggestions.removeAllViews();
        }
        mEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        updateJump();
    }

    /** 登录状态变了。游客和已登录都可以跟智能体说话；远程电脑和社区仍要账号。 */
    void applyAccountMode() {
        updateEmpty();
        refreshTargets();
        if (LemurXAccount.isSignedIn() && mThreadId != null && !mLoaded && !mLoading
                && mMessages.isEmpty()) {
            String id = mThreadId;
            mThreadId = null;
            load(id);
        }
    }

    private void paintAccountMode() {
        mGuestNote.setVisibility(View.GONE);
        if (!mListening) mInput.setHint(inputHintRes());
        mEmptyHint.setText(R.string.lemurx_muse_empty_hint);
    }

    private int inputHintRes() {
        if (onRemoteComputer()) return R.string.lemurx_muse_computer_ask;
        return R.string.lemurx_muse_input_hint;
    }

    /** 操作对象是一台远程电脑，而不是本机浏览器。 */
    private boolean onRemoteComputer() {
        String selected = LemurXComputer.operateTarget(getContext());
        return selected != null && !LemurXComputer.TARGET_BROWSER.equalsIgnoreCase(selected);
    }

    /** 列表比视口高且没贴底时显示"回到最新"。 */
    private void updateJump() {
        View child = mScroll.getChildAt(0);
        boolean show = mAskSheet.getVisibility() != View.VISIBLE
                && child != null
                && child.getHeight() > mScroll.getHeight() + dp(8)
                && !mStickToBottom
                && !mMessages.isEmpty();
        mJump.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /**
     * 开场建议是生活、工作、正在发生的事。过去的操作不占这些位置，收在「查看历史操作」里。
     * 点一下就发出去。
     */
    private void buildSuggestions() {
        Context context = getContext();
        String web = LemurXConsoleShell.tabWebUrl(mTab);
        mHistoryOpen = false;
        mHistory.clear();
        mGuides.clear();
        mGuides.addAll(fallbackSuggestions(context, web));
        paintSuggestions();
        final int gen = ++mSuggestGen;
        String thread = mThreadId != null ? mThreadId : "default";
        StringBuilder path = new StringBuilder("/api/tab-starters?id=");
        path.append(Uri.encode(thread));
        path.append("&lang=").append(Uri.encode(java.util.Locale.getDefault().getLanguage()));
        if (web != null) path.append("&url=").append(Uri.encode(web));
        MuseApi.get()
                .get(
                        path.toString(),
                        (value, error) -> {
                            if (gen != mSuggestGen) return;
                            if (!mMessages.isEmpty() || mLoading) return;
                            mHistory.clear();
                            mHistory.addAll(historyFrom(value));
                            paintSuggestions();
                        });
    }

    private List<String[]> fallbackSuggestions(Context context, @Nullable String web) {
        List<String[]> catalog = new ArrayList<>();
        catalog.add(guide(context, R.string.lemurx_muse_suggest_life, R.string.lemurx_muse_suggest_life_prompt));
        catalog.add(guide(context, R.string.lemurx_muse_suggest_work, R.string.lemurx_muse_suggest_work_prompt));
        catalog.add(guide(context, R.string.lemurx_muse_suggest_now, R.string.lemurx_muse_suggest_now_prompt));
        catalog.add(guide(context, R.string.lemurx_muse_suggest_errand, R.string.lemurx_muse_suggest_errand_prompt));
        catalog.add(guide(context, R.string.lemurx_muse_suggest_tomorrow, R.string.lemurx_muse_suggest_tomorrow_prompt));
        int day = (int) Math.floorMod(System.currentTimeMillis() / 86_400_000L, catalog.size());
        List<String[]> items = new ArrayList<>();
        if (web != null) {
            items.add(new String[] {
                context.getString(R.string.lemurx_muse_suggest_summarize),
                context.getString(R.string.lemurx_muse_suggest_summarize_prompt, web)
            });
        }
        for (int i = 0; i < 3 && items.size() < 4; i++) {
            items.add(catalog.get((day + i) % catalog.size()));
        }
        return items;
    }

    private static String[] guide(Context context, int title, int prompt) {
        return new String[] {context.getString(title), context.getString(prompt)};
    }

    /** 历史操作。新服务放在 history；旧服务把它们塞在「继续查」推荐里。 */
    private static List<String[]> historyFrom(@Nullable Object value) {
        List<String[]> items = new ArrayList<>();
        if (!(value instanceof JSONObject)) return items;
        JSONObject json = (JSONObject) value;
        JSONArray history = json.optJSONArray("history");
        if (history != null) {
            items.addAll(rowsFrom(history));
            return items;
        }
        for (String[] row : rowsFrom(json.optJSONArray("items"))) {
            if (row[0].startsWith("Keep searching:") || row[0].startsWith("继续查：")) items.add(row);
        }
        return items;
    }

    private static List<String[]> rowsFrom(@Nullable JSONArray rows) {
        List<String[]> items = new ArrayList<>();
        if (rows == null) return items;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String title = row.optString("title", "").trim();
            String prompt = row.optString("prompt", "").trim();
            if (title.isEmpty() || prompt.isEmpty()) continue;
            items.add(new String[] {title, prompt});
        }
        return items;
    }

    private void paintSuggestions() {
        Context context = getContext();
        mSuggestions.removeAllViews();
        for (String[] item : mGuides) addSuggestChip(context, item, false);
        TextView history = new TextView(context);
        history.setText(R.string.lemurx_muse_history);
        history.setTextColor(MusePage.accent(context));
        history.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        history.setPadding(dp(4), dp(10), dp(4), dp(4));
        history.setClickable(true);
        history.setFocusable(true);
        history.setOnClickListener(
                v -> {
                    LemurXHaptics.tick(v);
                    mHistoryOpen = !mHistoryOpen;
                    paintSuggestions();
                });
        mSuggestions.addView(history);
        if (!mHistoryOpen) return;
        if (mHistory.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(R.string.lemurx_muse_history_empty);
            empty.setTextColor(MusePage.muted(context));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            empty.setPadding(dp(4), dp(6), dp(4), dp(4));
            mSuggestions.addView(empty);
            return;
        }
        for (String[] item : mHistory) addSuggestChip(context, item, true);
    }

    private void addSuggestChip(Context context, String[] item, boolean past) {
        TextView chip = new TextView(context);
        chip.setText(item[0]);
        chip.setTextColor(past ? MusePage.muted(context) : MusePage.text(context));
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(MusePage.rounded(MusePage.surface(context), 14, context));
        chip.setPadding(dp(16), dp(12), dp(16), dp(12));
        chip.setClickable(true);
        chip.setFocusable(true);
        final String prompt = item[1];
        chip.setOnClickListener(
                v -> {
                    LemurXHaptics.tick(v);
                    mAskQuiet++;
                    mInput.setText(prompt);
                    send();
                    mAskQuiet--;
                });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        mSuggestions.addView(chip, lp);
    }

    // ---------------------------------------------------------------- 渲染

    private void append(Message message) {
        mMessages.add(message);
        warmOffice(message);
        Context context = getContext();
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        view.setLineSpacing(0, 1.28f);
        // 系统选择（复制、全选、分享、拖动手柄）要求这条文字能拿到焦点。
        // 滚动容器平时挡住子孙焦点，点一下不会把输入框的键盘收掉；按下时再放开。
        view.setTextIsSelectable(true);
        view.setHighlightColor(MusePage.night(context) ? 0x668AB4F8 : 0x661A73E8);
        view.setCustomSelectionActionModeCallback(selectionMode(view));
        view.setOnTouchListener((v, event) -> trackSelectionArm((TextView) v, event));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        String mood = "user".equals(message.role) ? moodFor(message.text) : "";
        if ("user".equals(message.role)) {
            view.setTextColor(MusePage.text(getContext()));
            view.setBackground(MusePage.rounded(MusePage.surface(getContext()), 20, context));
            view.setPadding(dp(14), dp(10), dp(14), dp(10));
            paintUser(view, message);
            lp.gravity = Gravity.END;
            lp.setMargins(dp(48), dp(10), mood.isEmpty() ? 0 : dp(16), mood.isEmpty() ? dp(10) : dp(16));
        } else {
            view.setTextColor(MusePage.text(getContext()));
            view.setPadding(dp(2), dp(6), dp(2), dp(6));
            view.setMovementMethod(LinkSelectMovementMethod.getInstance());
            view.setLinkTextColor(MusePage.link(context));
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.setMargins(0, dp(6), 0, dp(6));
        }
        message.view = view;
        view.setLayoutParams(lp);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        if ("user".equals(message.role)) {
            FrameLayout.LayoutParams bubbleLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bubbleLp.gravity = Gravity.END;
            bubbleLp.setMargins(
                    dp(48), dp(10), mood.isEmpty() ? 0 : dp(16), mood.isEmpty() ? dp(10) : dp(16));
            view.setLayoutParams(bubbleLp);
            FrameLayout stack = new FrameLayout(context);
            stack.addView(view);
            if (!mood.isEmpty()) {
                View badge = moodBadge(mood);
                FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(dp(30), dp(30));
                badgeLp.gravity = Gravity.END | Gravity.BOTTOM;
                stack.addView(badge, badgeLp);
            }
            row.addView(stack, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            LinearLayout body = new LinearLayout(context);
            body.setOrientation(LinearLayout.VERTICAL);
            message.body = body;
            row.addView(body, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            renderInto(view, message.text);
        }
        message.row = row;
        if (!"user".equals(message.role)) row.addView(messageActions(message));
        bindFiles(message);
        if (!message.steps.isEmpty()) renderAgentLog(message);
        // 正在输入的那行始终压在列表最后。挂哪些气泡由窗口决定，不在这里整列加进去。
        if (!mBatch) scheduleWindow();
        updateEmpty();
    }

    /** 下一帧再决定挂哪些气泡，同一帧里的滚动和打字只算一次。 */
    private void scheduleWindow() {
        if (mBatch || mWindowPosted) return;
        mWindowPosted = true;
        postOnAnimation(
                () -> {
                    mWindowPosted = false;
                    layoutWindow();
                });
    }

    /**
     * 只把视口上下各一屏的气泡挂到列表上。更早和更晚的用空白撑高度，
     * 这样滚动时不会把整段对话画进同一张图层。
     */
    private void layoutWindow() {
        int width = mList.getWidth() - mList.getPaddingLeft() - mList.getPaddingRight();
        if (width <= 0) {
            if (!mWindowPosted) {
                mWindowPosted = true;
                mList.post(
                        () -> {
                            mWindowPosted = false;
                            layoutWindow();
                        });
            }
            return;
        }
        // 选区还在时摘掉气泡，把手和工具条会一起没掉。
        if (mSelecting != null) return;
        int n = mMessages.size();
        int[] heights = new int[n];
        int content = 0;
        for (int i = 0; i < n; i++) {
            heights[i] = heightOf(mMessages.get(i), width);
            content += heights[i];
        }
        int extra = mList.getPaddingTop() + mList.getPaddingBottom();
        if (mShowTyping) {
            mTyping.measure(
                    View.MeasureSpec.makeMeasureSpec(Math.max(width, 1), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            extra += mTyping.getMeasuredHeight();
        }
        int viewport = Math.max(mScroll.getHeight(), 1);
        int maxScroll = Math.max(0, content + extra - viewport);
        // 窗口跟着当前滚动位置。贴底只在没按住屏幕时由 pinIfStuck 做，不能在这里把历史拽回去。
        int scroll = mHoldBottom && !mUserDrag ? maxScroll : mScroll.getScrollY();
        if (scroll > maxScroll) scroll = maxScroll;
        int from = Math.max(0, scroll - viewport);
        int to = scroll + viewport + viewport;
        int start = 0;
        int end = n;
        int acc = 0;
        for (int i = 0; i < n; i++) {
            if (acc + heights[i] <= from) start = i + 1;
            if (acc >= to && end == n) end = i;
            acc += heights[i];
        }
        int top = 0;
        for (int i = 0; i < start; i++) top += heights[i];
        int bot = 0;
        for (int i = end; i < n; i++) bot += heights[i];
        // 视口里的气泡变高不用重挂；只有窗口外的高度变了才重算空白。
        if (start == mWinStart && end == mWinEnd && top == mTopSpan && bot == mBotSpan
                && (n == 0 || mList.getChildCount() > 0)) {
            if (mHoldBottom && !mUserDrag) mScroll.post(this::pinIfStuck);
            return;
        }
        mWinStart = start;
        mWinEnd = end;
        mTopSpan = top;
        mBotSpan = bot;
        mList.removeAllViews();
        if (top > 0) {
            mList.addView(mTopGap, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, top));
        }
        for (int i = start; i < end; i++) {
            Message message = mMessages.get(i);
            View shown = message.row != null ? message.row : message.view;
            if (shown == null) continue;
            if (shown.getParent() instanceof ViewGroup) {
                ((ViewGroup) shown.getParent()).removeView(shown);
            }
            mList.addView(shown, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (bot > 0) {
            mList.addView(mBotGap, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, bot));
        }
        if (mShowTyping) attachTypingRow();
        final int anchor = scroll;
        mScroll.scrollTo(0, anchor);
        if (mHoldBottom) {
            mScroll.post(this::finishPinBottom);
        } else {
            mScroll.post(() -> {
                if (mStickToBottom || mHoldBottom) return;
                mScroll.scrollTo(0, anchor);
            });
        }
    }

    private int heightOf(Message message, int width) {
        View view = message.row != null ? message.row : message.view;
        if (view == null) return 0;
        if (message.cachedHeight > 0 && message.cachedWidth == width && message.cachedGen == message.gen) {
            return message.cachedHeight;
        }
        int widthSpec = "user".equals(message.role)
                ? View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST)
                : View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        view.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int margins = 0;
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            margins = lp.topMargin + lp.bottomMargin;
        }
        message.cachedHeight = view.getMeasuredHeight() + margins;
        message.cachedWidth = width;
        message.cachedGen = message.gen;
        return message.cachedHeight;
    }

    /** 把"正在输入"那一行放到列表末尾（已经在别处就先摘下来）。 */
    private void attachTypingRow() {
        if (mTyping.getParent() == mList && mList.indexOfChild(mTyping) == mList.getChildCount() - 1) {
            return;
        }
        if (mTyping.getParent() instanceof ViewGroup) {
            ((ViewGroup) mTyping.getParent()).removeView(mTyping);
        }
        mList.addView(mTyping, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void renderInto(TextView view, String markdown) {
        Message message = messageFor(view);
        if (message != null && message.body != null) {
            boolean streaming =
                    message == mTypeMessage && (!mTypeFinished || mTyped < message.text.length());
            MuseRich.show(
                    message.body,
                    replyBesideCommand(message, markdown),
                    streaming,
                    this::openInNewTab,
                    this::styleSelectable);
            view.setVisibility(View.GONE);
            bindFiles(message);
            return;
        }
        CharSequence body =
                MuseMarkdown.render(markdown, MusePage.text(getContext()), this::openInNewTab);
        view.setText(body);
        view.setVisibility(TextUtils.isEmpty(body) ? View.GONE : View.VISIBLE);
        if (message != null) bindFiles(message);
    }

    /** 回复里的文字可以长按选择，点一下不会把键盘收掉。 */
    private void styleSelectable(TextView view) {
        view.setTextIsSelectable(true);
        view.setHighlightColor(MusePage.night(getContext()) ? 0x668AB4F8 : 0x661A73E8);
        view.setCustomSelectionActionModeCallback(selectionMode(view));
        view.setOnTouchListener((v, event) -> trackSelectionArm((TextView) v, event));
        view.setMovementMethod(LinkSelectMovementMethod.getInstance());
        view.setLinkTextColor(MusePage.link(getContext()));
    }

    private @Nullable Message messageFor(TextView view) {
        for (Message message : mMessages) {
            if (message.view == view) return message;
        }
        return null;
    }

    /** 开始打一条新的助手回复（或工具调用后的下一段）。 */
    private void beginType(Message message) {
        mTypeMessage = message;
        mTyped = 0;
        mTypeAt = 0;
        mTypeFinished = false;
        scheduleType();
    }

    /**
     * 打字机：目标文本已经在 {@link Message#text} 里攒着。按经过的时间匀速往前，
     * 积压多了才略加快，每一帧都按已经写完的 Markdown 排，不把半截符号铺出来。
     */
    private void scheduleType() {
        if (mTypePending) return;
        mTypePending = true;
        postOnAnimation(this::tickType);
    }

    private void tickType() {
        mTypePending = false;
        Message m = mTypeMessage;
        if (m == null || m.view == null) return;
        int have = m.text.length();
        if (mTyped < have) {
            long now = android.os.SystemClock.uptimeMillis();
            long dt = mTypeAt == 0 ? 16 : Math.min(48, Math.max(8, now - mTypeAt));
            mTypeAt = now;
            int backlog = have - mTyped;
            float perSec = backlog > 400 ? 120f : backlog > 120 ? 64f : backlog > 24 ? 32f : 20f;
            int step = Math.max(1, Math.round(perSec * dt / 1000f));
            if (backlog < 8) step = 1;
            else step = Math.min(step, backlog > 400 ? 5 : 2);
            mTyped = snapMark(m.text, Math.min(have, mTyped + step));
            m.gen++;
            renderInto(m.view, m.text.substring(0, mTyped));
            if (mStickToBottom) scrollToBottom(false);
            updateJump();
            scheduleWindow();
            scheduleType();
            return;
        }
        mTypeAt = 0;
        if (mTypeFinished && !TextUtils.isEmpty(m.text)) {
            m.gen++;
            renderInto(m.view, m.text);
            if (mStickToBottom) scrollToBottom(false);
            updateJump();
            scheduleWindow();
        }
    }

    /** 不把一个字拆成两半，也不停在 ** 、~~ 、` 的中间。 */
    private static int snapMark(String text, int index) {
        if (index <= 0) return 0;
        if (index >= text.length()) return text.length();
        if (Character.isHighSurrogate(text.charAt(index - 1))) index++;
        if (index < text.length()) {
            char previous = text.charAt(index - 1);
            char next = text.charAt(index);
            if ((previous == '*' || previous == '~' || previous == '`') && next == previous) {
                index++;
            }
        }
        return Math.min(index, text.length());
    }

    private void scrollToBottom(boolean smooth) {
        mScroll.post(
                () -> {
                    // 手指还在翻历史时，这一帧排出来的贴底作废。
                    if (mUserDrag) return;
                    // 不用 fullScroll：它会把焦点挪到列表里的文字上，输入框失焦、键盘缩回去。
                    if (mHoldBottom && !smooth) {
                        finishPinBottom();
                        return;
                    }
                    View child = mScroll.getChildAt(0);
                    int bottom = child == null ? 0 : Math.max(0, child.getHeight() - mScroll.getHeight());
                    if (smooth) {
                        mScroll.smoothScrollTo(0, bottom);
                    } else {
                        mScroll.scrollTo(0, bottom);
                    }
                    // 思考行和键盘收起都会在这一帧之后才改高度，下一帧再贴到真正的底。
                    if (mStickToBottom || mHoldBottom) mScroll.post(this::pinIfStuck);
                });
    }

    /** 贴底时滚到内容的真实底部，把思考中那一行露出来。手指还在列表上时不动。 */
    private void pinIfStuck() {
        if (mUserDrag) return;
        if (!mStickToBottom && !mHoldBottom) return;
        View child = mScroll.getChildAt(0);
        if (child == null) return;
        int target = Math.max(0, child.getHeight() - mScroll.getHeight());
        if (Math.abs(mScroll.getScrollY() - target) <= dp(2)) {
            if (mHoldBottom) finishPinBottom();
            return;
        }
        mScroll.scrollTo(0, target);
        if (mHoldBottom) finishPinBottom();
    }

    /** 滚到最新一条后再放开滚动监听，这样中途的 0 位移不会被当成用户离开了底部。 */
    private void finishPinBottom() {
        View child = mScroll.getChildAt(0);
        int bottom = child == null ? 0 : Math.max(0, child.getHeight() - mScroll.getHeight());
        mScroll.scrollTo(0, bottom);
        mStickToBottom = true;
        mHoldBottom = false;
        updateJump();
    }

    private void showError(@Nullable String error) {
        // 没登录时输入只是搜索，访问密钥对不上是服务端的门，不拿给用户看。
        if (error != null && !LemurXAccount.isSignedIn()
                && error.toLowerCase(java.util.Locale.ROOT).contains("access key")) {
            error = null;
        }
        if (error != null) error = readableError(error);
        mError.setText(error == null ? "" : error);
        mError.setVisibility(error == null ? View.GONE : View.VISIBLE);
    }

    /** 模型接口的原始 JSON 不铺在对话里。短的、人能读的错误原样留着。 */
    private String readableError(String error) {
        String trimmed = error.trim();
        boolean raw = trimmed.startsWith("{")
                || trimmed.startsWith("[")
                || trimmed.contains("INVALID_ARGUMENT")
                || trimmed.contains("model turn")
                || trimmed.length() > 240;
        return raw ? getContext().getString(R.string.lemurx_muse_run_failed) : error;
    }

    private void showStatus(@Nullable String status) {
        if (status == null) {
            mShowTyping = false;
            if (mTyping.getParent() == mList) mList.removeView(mTyping);
        } else {
            mShowTyping = true;
            // "思考中"只亮点不写字；干活时写明在干什么。
            boolean thinking = status.equals(getContext().getString(R.string.lemurx_muse_thinking));
            mStatus.setText(thinking ? "" : status);
            mStatus.setVisibility(thinking ? View.GONE : View.VISIBLE);
            attachTypingRow();
            if (mStickToBottom) scrollToBottom(true);
        }
        if (mThreadId != null) {
            if (status == null) {
                sLive.remove(mThreadId);
            } else {
                sLive.put(mThreadId, status);
            }
        }
        LemurXDeviceBridge.noteAgentPresence();
        LemurXConsoleShell.refreshPipStatus();
    }

    // ---------------------------------------------------------------- 发送 / 停止

    private void onSendClicked() {
        LemurXHaptics.click(mSend);
        if (mRun != null) {
            stop();
            return;
        }
        hideKeyboard();
        send();
    }

    /** 窗口离开前台：停掉这一轮，留一条继续。没有正在进行的操作就不动。 */
    void suspendForBackground() {
        if (mSuspended) return;
        if (onRemoteComputer()) return;
        boolean drivingHere = mRun != null
                || (LemurXDeviceBridge.isHoldingTab()
                        && LemurXDeviceBridge.anchorTabId() == mTab.getId());
        if (!drivingHere) return;
        mSuspended = true;
        mSuspend.setVisibility(View.VISIBLE);
        LemurXDeviceBridge.markSuspended();
        if (mRun != null) {
            stop();
            return;
        }
        int anchor = LemurXDeviceBridge.anchorTabId();
        LemurXDeviceBridge.releaseScreen();
        if (anchor != Tab.INVALID_TAB_ID) LemurXConsoleShell.returnToChat(anchor);
    }

    private void resumeSuspended() {
        if (!mSuspended || mRun != null) return;
        mAskQuiet++;
        mInput.setText(getContext().getString(R.string.lemurx_agent_resume_prompt));
        send();
        mAskQuiet--;
    }

    private void send() {
        if (LemurXKillSwitch.isDisabled()) return;
        if (mSuspended) {
            mSuspended = false;
            mSuspend.setVisibility(View.GONE);
            LemurXDeviceBridge.markResumed();
        }
        if (mRun != null || mThreadId == null) return;
        if (mAttachReading) {
            showError(getContext().getString(R.string.lemurx_muse_attach_wait));
            return;
        }
        String typed = mInput.getText().toString().trim();
        if (typed.isEmpty() && mPending.isEmpty()) return;
        // 用户把验证码发回来了，这一页可以继续操作。验证码正文只留在对话里。
        LemurXDeviceBridge.clearCaptcha();
        if (mListening) endVoice(false);
        hideKeyboard();
        LemurXDeviceBridge.clearActionLog();
        List<Attach> files = new ArrayList<>(mPending);
        mPending.clear();
        renderAttach();
        String text = typed;
        mAskQuiet++;
        mInput.setText("");
        mAskQuiet--;
        hideAskSheet();
        showError(null);
        List<LemurXArchive.Proposal> noticed = new ArrayList<>();
        for (Attach file : files) {
            noticed.addAll(LemurXArchive.harvest(file.excerpt));
        }
        noticed.addAll(LemurXArchive.harvest(text));
        for (Attach file : files) {
            LemurXFiles.remember(getContext(), file.name, file.kind, file.excerpt);
            file.excerpt = LemurXArchive.maskValues(file.excerpt, noticed);
        }
        text = LemurXArchive.maskValues(text, noticed);
        clearBasis();
        Message user = new Message(UUID.randomUUID().toString(), "user", text, files);
        append(user);
        for (Attach file : files) askFileShare(file);
        mArchiveOffers.addAll(noticed);
        showArchiveOffers();
        rememberTitle();
        notifyTitleChanged();
        mStickToBottom = true;
        scrollToBottom(true);
        persist();

        Message assistant = new Message(UUID.randomUUID().toString(), "assistant", "");
        mStreaming = assistant;
        setRunning(true);
        showStatus(getContext().getString(R.string.lemurx_muse_thinking));
        final String thread = mThreadId;
        final int gen = ++mRunGen;
        mRun =
                MuseApi.get()
                        .run(
                                thread,
                                withAttachments(withTabContext(outbound(files)), files),
                                tabContext(),
                                new MuseApi.RunListener() {
                                    @Override
                                    public void onTextStart(String messageId) {
                                        if (gen != mRunGen || !thread.equals(mThreadId)) return;
                                        mArchiveHold = "";
                                        Message current = mStreaming;
                                        if (current == null) return;
                                        if (current.view == null) {
                                            append(current);
                                            beginType(current);
                                        } else if (!TextUtils.isEmpty(current.text)) {
                                            // 一轮里第二段文字（工具调用之后）：另起一条消息。
                                            Message next = new Message(messageId, "assistant", "");
                                            next.logFrom = LemurXDeviceBridge.actionLog().size();
                                            mStreaming = next;
                                            append(next);
                                            beginType(next);
                                        } else {
                                            // 工具卡已经先挂上了（电脑运行等），正文才到：接着打这一条。
                                            beginType(current);
                                        }
                                        showStatus(null);
                                    }

                                    @Override
                                    public void onTextDelta(String messageId, String delta) {
                                        if (gen != mRunGen || !thread.equals(mThreadId)) return;
                                        Message current = mStreaming;
                                        if (current == null) return;
                                        if (current.view == null) {
                                            append(current);
                                            beginType(current);
                                        }
                                        current.text += LemurXArchive.maskSecrets(peelArchive(delta));
                                        showStatus(null);
                                        scheduleType();
                                    }

                                    @Override
                                    public void onTextEnd(String messageId) {
                                        if (gen != mRunGen || !thread.equals(mThreadId)) return;
                                        flushArchiveHold();
                                        showArchiveOffers();
                                        // 打字机还在追：追上后再渲 markdown，这里不抢。
                                        scheduleType();
                                    }

                                    @Override
                                    public void onToolCall(String toolName) {
                                        if (gen != mRunGen || !thread.equals(mThreadId)) return;
                                        showStatus(getContext().getString(
                                                R.string.lemurx_muse_working, toolLabel(toolName)));
                                    }

                                    @Override
                                    public void onToolResult(String toolCallId, String content) {
                                        if (gen != mRunGen || !thread.equals(mThreadId)) return;
                                        takeWrittenFile(content);
                                        takeComputerRun(content);
                                        // 工具已经回来：不要还停在"browse web"，下一轮模型思考时换成思考中。
                                        showStatus(getContext().getString(
                                                R.string.lemurx_muse_thinking));
                                    }

                                    @Override
                                    public void onOwnComputer(JSONObject value) {
                                        if (gen != mRunGen || !thread.equals(mThreadId)) return;
                                        applyOwnComputer(value);
                                    }

                                    @Override
                                    public void onFinished() {
                                        if (gen != mRunGen || !thread.equals(mThreadId)) return;
                                        flushArchiveHold();
                                        showArchiveOffers();
                                        finishRun(null);
                                    }

                                    @Override
                                    public void onError(String message) {
                                        if (gen != mRunGen || !thread.equals(mThreadId)) return;
                                        if (stalled(message)) {
                                            recoverFromServer(message);
                                            return;
                                        }
                                        finishRun(message);
                                    }
                                });
    }

    /** 连接断了或读超时：服务端多半已经把回复写进会话，拉回来，别停在工具状态上。 */
    private static boolean stalled(String message) {
        String m = message.toLowerCase(java.util.Locale.US);
        return m.contains("timed out")
                || m.contains("closed the connection")
                || m.contains("unexpected end of stream")
                || m.contains("connection reset");
    }

    private void recoverFromServer(String fallback) {
        if (mThreadId == null) {
            finishRun(fallback);
            return;
        }
        final String id = mThreadId;
        MuseApi.get()
                .get(
                        "/api/conversation?id=" + id,
                        (value, error) -> {
                            if (!id.equals(mThreadId)) return;
                            String recovered = lastAssistantText(value);
                            boolean shown = false;
                            if (recovered != null) {
                                for (Message m : mMessages) {
                                    if ("assistant".equals(m.role) && recovered.equals(m.text)) {
                                        shown = true;
                                        break;
                                    }
                                }
                            }
                            finishRun(null);
                            if (recovered != null && !shown) {
                                append(new Message(UUID.randomUUID().toString(), "assistant", recovered));
                                persist();
                                if (mStickToBottom) scrollToBottom(true);
                            } else if (recovered == null) {
                                showError(fallback);
                            }
                        });
    }

    private static @Nullable String lastAssistantText(@Nullable Object value) {
        if (!(value instanceof JSONObject)) return null;
        JSONArray messages = ((JSONObject) value).optJSONArray("messages");
        if (messages == null) return null;
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            if (!"assistant".equals(m.optString("role", ""))) continue;
            String content = m.optString("content", "");
            if (!TextUtils.isEmpty(content)) return content;
        }
        return null;
    }

    private void finishRun(@Nullable String error) {
        mRun = null;
        Message current = mStreaming;
        mStreaming = null;
        setRunning(false);
        showStatus(null);
        if (current != null && current.view != null && TextUtils.isEmpty(current.text) && current.files.isEmpty() && current.steps.isEmpty()) {
            View gone = current.row != null ? current.row : current.view;
            if (gone.getParent() instanceof ViewGroup) {
                ((ViewGroup) gone.getParent()).removeView(gone);
            }
            mMessages.remove(current);
            if (mTypeMessage == current) mTypeMessage = null;
            mWinStart = -1;
            mWinEnd = -1;
            mTopSpan = -1;
            mBotSpan = -1;
            scheduleWindow();
        } else if (current != null) {
            if (TextUtils.isEmpty(current.text) && current.view != null) current.view.setVisibility(View.GONE);
            mTypeFinished = true;
            if (mTypeMessage == null) mTypeMessage = current;
            scheduleType();
        }
        if (error != null) showError(error);
        persist();
        // 这一轮确实在操作网页：放开锁，回到对话，让用户看结果或接着回答。
        boolean drove = LemurXDeviceBridge.isHoldingTab();
        int anchor = LemurXDeviceBridge.anchorTabId();
        LemurXDeviceBridge.releaseScreen();
        if (drove && anchor != Tab.INVALID_TAB_ID) LemurXConsoleShell.returnToChat(anchor);
        refreshAgentPages();
        if (mStickToBottom) scrollToBottom(true);
    }

    /** 对话框里标明 Agent 占着几个页面，避免用户只能去标签管理里数。 */
    private void refreshAgentPages() {
        TextView pages = mPages;
        if (pages == null) return;
        int count = LemurXDeviceBridge.pageCount();
        if (count <= 0) {
            pages.setVisibility(View.GONE);
            return;
        }
        String host = LemurXDeviceBridge.pageHost();
        if (count == 1) {
            pages.setText(TextUtils.isEmpty(host)
                    ? getContext().getString(R.string.lemurx_muse_agent_tab)
                    : getContext().getString(R.string.lemurx_muse_agent_tab_host, host));
        } else {
            pages.setText(getContext().getString(R.string.lemurx_muse_agent_tabs, count));
        }
        pages.setVisibility(View.VISIBLE);
    }

    /** 点一下「Agent 操作」旁边的问号：说明出现一小会儿再收起。 */
    private void showTargetHint() {
        if (mTargetHint == null || mHideTargetHint == null) return;
        mTargetHint.setVisibility(View.VISIBLE);
        mTargetHint.removeCallbacks(mHideTargetHint);
        mTargetHint.postDelayed(mHideTargetHint, 2200);
    }

    /**
     * 验证码回到这路对话。停掉正在跑的那一轮，用户才能把验证码发出去，Agent 再接着做。
     */
    void askCaptcha(@Nullable String reason) {
        if (mRun != null) {
            mRunGen++;
            mRun.cancel();
            finishRun(null);
        }
        String ask = TextUtils.isEmpty(reason)
                ? getContext().getString(R.string.lemurx_captcha_ask)
                : reason;
        Message last = mMessages.isEmpty() ? null : mMessages.get(mMessages.size() - 1);
        if (last == null || !"assistant".equals(last.role) || !ask.equals(last.text)) {
            append(new Message(UUID.randomUUID().toString(), "assistant", ask));
        }
        mStickToBottom = true;
        scrollToBottom(true);
        persist();
        mInput.requestFocus();
        mInput.post(
                () -> {
                    InputMethodManager imm =
                            (InputMethodManager)
                                    getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(mInput, InputMethodManager.SHOW_IMPLICIT);
                });
    }

    /** 用户点了停止，或者换会话 / 关页面。 */
    void stop() {
        MuseApi.Run run = mRun;
        if (run == null) return;
        run.cancel();
        paintAgentLog();
        Message current = mStreaming;
        if (current != null) {
            for (Attach file : current.files) {
                if ("computer".equals(file.kind) && file.computerRunId != null) {
                    LemurXComputer.Run crun = LemurXComputer.getRun(file.computerRunId);
                    if (crun != null && !crun.done) {
                        LemurXComputer.endRun(file.computerRunId, false, "用户中止了操作", null);
                    }
                    if ("…".equals(file.excerpt)) file.excerpt = "用户中止了操作";
                }
            }
        }
        finishRun(null);
    }


    private void setRunning(boolean running) {
        mBusy = running;
        mSend.setImageResource(running ? R.drawable.lemurx_muse_ic_stop : R.drawable.lemurx_muse_ic_send);
        mSend.setColorFilter(MusePage.onAccent(getContext()), PorterDuff.Mode.SRC_IN);
        mSend.setContentDescription(getContext().getString(
                running ? R.string.lemurx_muse_stop : R.string.lemurx_muse_send));
        paintMic();
        mAttach.setAlpha(running ? 0.35f : 1f);
    }

    // ---------------------------------------------------------------- 语音

    private void onMicClicked() {
        LemurXHaptics.click(mMic);
        if (hasInputText()) {
            if (mListening) endVoice(false);
            clearBasis();
            mInput.setText("");
            return;
        }
        if (mListening) {
            endVoice(true);
            return;
        }
        if (mBusy) return;
        WindowAndroid window = LemurXConsoleShell.windowAndroid();
        if (window == null) {
            showError(getContext().getString(R.string.lemurx_muse_voice_unavailable));
            return;
        }
        if (window.hasPermission(Manifest.permission.RECORD_AUDIO)) {
            startListening();
            return;
        }
        window.requestPermissions(
                new String[] {Manifest.permission.RECORD_AUDIO},
                (permissions, grantResults) ->
                        post(
                                () -> {
                                    if (!isAttachedToWindow() || mBusy) return;
                                    boolean granted = false;
                                    for (int result : grantResults) {
                                        if (result == PackageManager.PERMISSION_GRANTED) granted = true;
                                    }
                                    if (granted) startListening();
                                    else showError(getContext().getString(R.string.lemurx_muse_voice_denied));
                                }));
    }

    /** 听一句。说完（或短暂停顿）就把这句话发给 Agent，不用再点发送。 */
    private void startListening() {
        if (mListening || mBusy) return;
        Context context = getContext();
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            showError(context.getString(R.string.lemurx_muse_voice_unavailable));
            return;
        }
        hideKeyboard();
        if (mRecognizer == null) {
            mRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
            mRecognizer.setRecognitionListener(new VoiceListener());
        }
        mTextBeforeVoice = mInput.getText().toString();
        mVoiceDraft = null;
        mListening = true;
        paintMic();
        mInput.setHint(R.string.lemurx_muse_voice_listening);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        // 一句话说完、停一下，就当作这句结束。
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L);
        try {
            mRecognizer.startListening(intent);
        } catch (Exception e) {
            Log.i(TAG, "voice: %s", e.getMessage());
            mListening = false;
            paintMic();
            mInput.setHint(inputHintRes());
            showError(context.getString(R.string.lemurx_muse_voice_unavailable));
        }
    }

    /**
     * 结束这一轮听写。
     *
     * @param restore 用户取消时把输入框还原成开口之前的文字；说完要发送时不还原。
     */
    private void endVoice(boolean restore) {
        boolean was = mListening;
        mListening = false;
        mVoiceDraft = null;
        paintMic();
        mInput.setHint(inputHintRes());
        if (was && restore) {
            mInput.setText(mTextBeforeVoice == null ? "" : mTextBeforeVoice);
        }
        SpeechRecognizer recognizer = mRecognizer;
        if (recognizer != null && was) {
            try {
                recognizer.cancel();
            } catch (Exception ignored) {
                // 识别器已经停了。
            }
        }
    }

    /** 识别器自己结束了：有文字就发出去，没有就把开口前的草稿放回来。 */
    private void finishVoice(@Nullable String spoken) {
        if (!mListening) return;
        mListening = false;
        mVoiceDraft = null;
        paintMic();
        mInput.setHint(inputHintRes());
        String said = spoken == null ? "" : spoken.trim();
        if (said.isEmpty()) {
            mInput.setText(mTextBeforeVoice == null ? "" : mTextBeforeVoice);
            return;
        }
        mInput.setText(said);
        send();
    }

    private void releaseVoice() {
        endVoice(false);
        SpeechRecognizer recognizer = mRecognizer;
        mRecognizer = null;
        if (recognizer == null) return;
        try {
            recognizer.destroy();
        } catch (Exception ignored) {
            // 页面已经拆掉。
        }
    }

    /** 输入框里已经有字。这时语音钮换成清除。 */
    private boolean hasInputText() {
        return mInput.getText() != null && mInput.getText().length() > 0;
    }

    private void paintMic() {
        Context context = getContext();
        if (hasInputText()) {
            mMic.setImageResource(R.drawable.lemurx_muse_ic_x);
            mMic.setBackground(MusePage.rounded(MusePage.track(context), 20, context));
            mMic.setColorFilter(MusePage.text(context), PorterDuff.Mode.SRC_IN);
            mMic.setContentDescription(context.getString(R.string.lemurx_muse_clear_input));
            mMic.setAlpha(1f);
        } else if (mListening) {
            mMic.setImageResource(R.drawable.lemurx_muse_ic_mic);
            mMic.setBackground(MusePage.rounded(MusePage.accent(context), 20, context));
            mMic.setColorFilter(MusePage.onAccent(context), PorterDuff.Mode.SRC_IN);
            mMic.setContentDescription(context.getString(R.string.lemurx_muse_voice_stop));
            mMic.setAlpha(1f);
        } else {
            mMic.setImageResource(R.drawable.lemurx_muse_ic_mic);
            mMic.setBackground(null);
            mMic.setColorFilter(MusePage.text(context), PorterDuff.Mode.SRC_IN);
            mMic.setContentDescription(context.getString(R.string.lemurx_muse_voice));
            mMic.setAlpha(mBusy ? 0.35f : 1f);
        }
    }

    private void showVoiceDraft(@Nullable String text) {
        if (!mListening || TextUtils.isEmpty(text)) return;
        mVoiceDraft = text;
        mInput.setText(text);
        mInput.setSelection(text.length());
    }

    private final class VoiceListener implements RecognitionListener {
        @Override
        public void onReadyForSpeech(Bundle params) {}

        @Override
        public void onBeginningOfSpeech() {}

        @Override
        public void onRmsChanged(float rmsdB) {}

        @Override
        public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {}

        @Override
        public void onError(int error) {
            if (!mListening) return;
            String draft = mVoiceDraft;
            boolean useDraft =
                    !TextUtils.isEmpty(draft)
                            && (error == SpeechRecognizer.ERROR_NO_MATCH
                                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
            if (!useDraft
                    && error != SpeechRecognizer.ERROR_NO_MATCH
                    && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    && error != SpeechRecognizer.ERROR_CLIENT) {
                int message =
                        error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                                ? R.string.lemurx_muse_voice_denied
                                : R.string.lemurx_muse_voice_failed;
                showError(getContext().getString(message));
            }
            finishVoice(useDraft ? draft : null);
        }

        @Override
        public void onResults(Bundle results) {
            ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String spoken = list == null || list.isEmpty() ? mVoiceDraft : list.get(0);
            finishVoice(spoken);
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            ArrayList<String> list =
                    partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list == null || list.isEmpty()) return;
            showVoiceDraft(list.get(0));
        }

        @Override
        public void onEvent(int eventType, Bundle params) {}
    }

    private String toolLabel(String name) {
        Context context = getContext();
        switch (name) {
            case "write_file":
                return context.getString(R.string.lemurx_muse_tool_write);
            case "use_own_computer":
                return context.getString(R.string.lemurx_muse_tool_computer);
            case "delegate_task":
                return context.getString(R.string.lemurx_muse_tool_delegate);
            case "browse":
            case "browse_web":
            case "open_page":
            case "read_page":
            case "browser_read":
                return context.getString(R.string.lemurx_muse_tool_browse);
            case "search":
            case "web_search":
                return context.getString(R.string.lemurx_muse_tool_search);
            default:
                return name.replace('_', ' ');
        }
    }

    /** 档案标记还没写完就先别画出来。写完的标记从正文拿掉，改成对话里的确认。 */
    private String peelArchive(String delta) {
        String all = mArchiveHold + (delta == null ? "" : delta);
        mArchiveHold = "";
        int start = all.lastIndexOf("档案");
        if (start >= 0 && all.indexOf('\n', start) < 0) {
            String tail = all.substring(start);
            int pipes = 0;
            for (int i = 0; i < tail.length(); i++) if (tail.charAt(i) == '|') pipes++;
            if (pipes < 4 && tail.length() < 180) {
                mArchiveHold = tail;
                all = all.substring(0, start);
            }
        }
        return LemurXArchive.extractMarkers(all, mArchiveOffers);
    }

    private void flushArchiveHold() {
        Message current = mStreaming;
        if (!mArchiveHold.isEmpty() && current != null) {
            current.text += LemurXArchive.maskSecrets(
                    LemurXArchive.extractMarkers(mArchiveHold, mArchiveOffers));
            current.gen++;
        }
        mArchiveHold = "";
    }

    private void showArchiveOffers() {
        if (mArchiveOffers.isEmpty()) return;
        List<LemurXArchive.Proposal> batch = new ArrayList<>(mArchiveOffers);
        mArchiveOffers.clear();
        boolean any = false;
        for (LemurXArchive.Proposal proposal : batch) {
            if (proposal.label.isEmpty() || proposal.value.isEmpty()) continue;
            if (LemurXArchive.already(getContext(), proposal)) continue;
            String asked = proposal.label + "\n" + proposal.value.toLowerCase(Locale.ROOT);
            if (!mArchiveAsked.add(asked)) continue;
            String line = proposal.redact
                    ? getContext().getString(
                            R.string.lemurx_archive_offer_secret, archiveKind(proposal.kind))
                    : getContext().getString(
                            R.string.lemurx_archive_offer,
                            archiveKind(proposal.kind),
                            proposal.label,
                            proposal.value);
            Message message = new Message(UUID.randomUUID().toString(), "assistant", line);
            message.uiOnly = true;
            append(message);
            addArchiveActions(message, proposal);
            any = true;
        }
        if (!any) return;
        persist();
        mStickToBottom = true;
        scrollToBottom(true);
    }

    private void addArchiveActions(Message message, LemurXArchive.Proposal proposal) {
        if (message.row == null) return;
        LinearLayout actions = new LinearLayout(getContext());
        actions.setTag("archive-offer");
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, 0, 0, dp(8));
        if (!proposal.redact) {
            actions.addView(archiveButton(R.string.lemurx_archive_keep_general, true, () ->
                    keepArchive(message, proposal, LemurXArchive.GENERAL)));
        }
        actions.addView(archiveButton(R.string.lemurx_archive_keep_sensitive, proposal.redact, () ->
                keepArchive(message, proposal, LemurXArchive.SENSITIVE)));
        actions.addView(archiveButton(R.string.lemurx_archive_skip, false, () ->
                dropArchive(message)));
        message.row.addView(actions);
    }

    private TextView archiveButton(int label, boolean filled, Runnable action) {
        TextView view = new TextView(getContext());
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        if (filled) {
            view.setTextColor(MusePage.onAccent(getContext()));
            view.setBackground(MusePage.rounded(MusePage.accent(getContext()), 14, getContext()));
        } else {
            view.setTextColor(MusePage.muted(getContext()));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        view.setLayoutParams(lp);
        view.setOnClickListener(v -> {
            LemurXHaptics.tick(v);
            action.run();
        });
        return view;
    }

    private void keepArchive(Message message, LemurXArchive.Proposal proposal, String section) {
        LemurXArchive.save(
                getContext(), LemurXArchive.inSection(proposal, section), "chat");
        message.text = getContext().getString(R.string.lemurx_archive_saved);
        if (message.view != null) message.view.setText(message.text);
        dropArchiveButtons(message);
        persist();
    }

    private void dropArchive(Message message) {
        message.text = getContext().getString(R.string.lemurx_archive_skipped);
        if (message.view != null) message.view.setText(message.text);
        dropArchiveButtons(message);
        persist();
    }

    private void dropArchiveButtons(Message message) {
        if (message.row == null) return;
        for (int i = message.row.getChildCount() - 1; i >= 0; i--) {
            if ("archive-offer".equals(message.row.getChildAt(i).getTag())) {
                message.row.removeViewAt(i);
            }
        }
    }

    private void askFileShare(Attach file) {
        Message message = new Message(
                UUID.randomUUID().toString(),
                "assistant",
                getContext().getString(R.string.lemurx_archive_file_ask, file.name));
        message.uiOnly = true;
        append(message);
        if (message.row == null) return;
        LinearLayout actions = new LinearLayout(getContext());
        actions.setTag("archive-offer");
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, 0, 0, dp(8));
        actions.addView(archiveButton(R.string.lemurx_archive_share_off, false, () ->
                chooseFileShare(message, file, LemurXFiles.SHARE_OFF)));
        actions.addView(archiveButton(R.string.lemurx_archive_share_index, true, () ->
                chooseFileShare(message, file, LemurXFiles.SHARE_INDEX)));
        actions.addView(archiveButton(R.string.lemurx_archive_share_body, false, () ->
                chooseFileShare(message, file, LemurXFiles.SHARE_BODY)));
        message.row.addView(actions);
    }

    private void chooseFileShare(Message message, Attach file, String share) {
        LemurXFiles.setShare(getContext(), file.name, file.kind, share);
        message.text = getContext().getString(R.string.lemurx_archive_saved);
        if (message.view != null) message.view.setText(message.text);
        dropArchiveButtons(message);
        persist();
    }

    private String archiveKind(String kind) {
        if (LemurXArchive.WORK.equals(kind)) {
            return getContext().getString(R.string.lemurx_archive_kind_work);
        }
        if (LemurXArchive.SOCIAL.equals(kind)) {
            return getContext().getString(R.string.lemurx_archive_kind_social);
        }
        if (LemurXArchive.PREFERENCE.equals(kind)) {
            return getContext().getString(R.string.lemurx_archive_kind_preference);
        }
        if (LemurXArchive.CONTACT.equals(kind) || kind == null || kind.isEmpty()) {
            return getContext().getString(R.string.lemurx_archive_kind_contact);
        }
        return kind;
    }

    // ---------------------------------------------------------------- 标签页上下文

    /**
     * 这个标签页正在看的网页，加上当前操作对象，作为 AG-UI context 交给服务端拼进系统提示。
     * 网址和机器名只是上下文，页面本身的内容是不可信数据，这一点由服务端的提示词交代。
     * 操作对象决定这一轮是读本机网页，还是在 Tailscale 的那台机器上干活。
     */
    private @Nullable JSONArray tabContext() {
        try {
            JSONArray out = new JSONArray();
            JSONObject target = new JSONObject();
            target.put("description", "Operation target");
            target.put("value", operationTargetValue());
            out.put(target);
            String web = LemurXConsoleShell.tabWebUrl(mTab);
            if (web != null) {
                JSONObject ctx = new JSONObject();
                ctx.put("description", "Web page open in this tab");
                ctx.put("value", web);
                out.put(ctx);
            }
            JSONObject archive = new JSONObject();
            archive.put("description", "Person archive");
            archive.put("value", LemurXArchive.contextValue(getContext()));
            out.put(archive);
            return out;
        } catch (JSONException e) {
            return null;
        }
    }

    /** 这一轮告诉模型的操作对象。机器不在列表里时退回本机浏览器，避免还去读一个已经不该读的网页。 */
    private String operationTargetValue() {
        String selected = LemurXComputer.operateTarget(getContext());
        if (LemurXComputer.TARGET_BROWSER.equals(selected)) {
            return "This Aero browser. The user selected it as the only place to act. "
                    + "Open and read pages here. Do not call use_own_computer. "
                    + "Do not operate a Tailscale machine.";
        }
        String name = selected;
        String online = "unknown";
        for (int i = 0; i < mTargetDevices.length(); i++) {
            JSONObject device = mTargetDevices.optJSONObject(i);
            if (device == null || device.optBoolean("self", false)) continue;
            String ip = device.optString("ip", "");
            if (!ip.equalsIgnoreCase(selected)) continue;
            String host = device.optString("name", "");
            if (!host.isEmpty()) name = host;
            online = device.optBoolean("online", false) ? "online" : "offline";
            return "Tailscale machine \"" + name + "\" at " + ip + ". It is " + online
                    + ". The user selected this machine as the only place to act. "
                    + "Carry the request out with use_own_computer on that machine. "
                    + "Do not call browse_web, browser_look, or any browser tool. "
                    + "Do not open or read a page in the Aero browser.";
        }
        return "This Aero browser. The user selected it as the only place to act. "
                + "Open and read pages here. Do not call use_own_computer. "
                + "Do not operate a Tailscale machine.";
    }

    /**
     * 这一轮的操作步骤挂在当前那条回复上，跟着对话走。默认只露一行，点开才看每一步。
     * 下一轮发送会清掉还在进行的日志；已经写在回复上的步骤留在那一条里。
     */
    private void paintAgentLog() {
        Message host = mStreaming;
        if (host == null) return;
        java.util.ArrayList<String> all = LemurXDeviceBridge.actionLog();
        int from = Math.max(0, Math.min(host.logFrom, all.size()));
        host.steps.clear();
        host.steps.addAll(all.subList(from, all.size()));
        if (host.steps.isEmpty()) {
            if (host.logBox != null) host.logBox.setVisibility(View.GONE);
            return;
        }
        if (host.view == null) append(host);
        renderAgentLog(host);
    }

    /** 把这条回复上已经记下的步骤画出来。折起时只留标题。 */
    private void renderAgentLog(Message host) {
        java.util.ArrayList<String> lines = host.steps;
        if (lines.isEmpty() || host.row == null) return;
        Context context = getContext();
        if (host.logBox == null) {
            host.logBox = new LinearLayout(context);
            host.logBox.setOrientation(LinearLayout.VERTICAL);
            host.logBox.setBackground(MusePage.rounded(
                    MusePage.night(context) ? 0xD91A1D24 : 0xE8F4F6F8, 14, context));
            host.logBox.setPadding(dp(12), dp(8), dp(12), dp(8));
            LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            boxLp.bottomMargin = dp(6);
            host.row.addView(host.logBox, 0, boxLp);
        }
        host.logBox.setVisibility(View.VISIBLE);
        host.logBox.removeAllViews();
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText(context.getString(R.string.lemurx_agent_log_steps, lines.size()));
        title.setTextColor(MusePage.text(context));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView toggle = new TextView(context);
        toggle.setText(host.logOpen ? "▾" : "▸");
        toggle.setTextColor(MusePage.muted(context));
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        toggle.setPadding(dp(8), 0, 0, 0);
        head.addView(toggle);
        head.setOnClickListener(v -> {
            host.logOpen = !host.logOpen;
            renderAgentLog(host);
        });
        host.logBox.addView(head);
        if (host.logOpen) {
            TextView copy = new TextView(context);
            copy.setText(R.string.lemurx_muse_command_copy);
            copy.setTextColor(MusePage.muted(context));
            copy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            copy.setPadding(0, dp(4), 0, dp(2));
            StringBuilder joined = new StringBuilder();
            for (String line : lines) {
                if (joined.length() > 0) joined.append('\n');
                joined.append(line);
            }
            copy.setOnClickListener(v -> copyPlain(joined.toString(), copy));
            host.logBox.addView(copy);
            int ink = MusePage.night(context) ? 0xE6FFFFFF : MusePage.text(context);
            for (String line : lines) {
                TextView row = new TextView(context);
                row.setText(line);
                row.setTextColor(ink);
                row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                row.setLineSpacing(dp(2), 1f);
                row.setPadding(0, dp(3), 0, dp(3));
                styleSelectable(row);
                host.logBox.addView(row);
            }
        }
        host.gen++;
        scheduleWindow();
    }

    /** 打开应用或回到前台时刷新这一页的设备点。节点上线由 {@link LemurXComputer#ensureNodeOnline} 合并。 */
    void bringTargetsOnline() {
        refreshTargets();
    }

    /**
     * 拉被控制的那台电脑。游客没有远程电脑，仍显示本机浏览器，让人知道 Agent 能操作这个浏览器。
     * 先等节点上线再读设备列表。列表接口本身不会执行 tailscale up，并行去读会把还没起来的机器画成离线。
     */
    private void refreshTargets() {
        final int gen = ++mTargetGen;
        mControlledHost = LemurXComputer.host(getContext());
        if (!LemurXAccount.isSignedIn()) {
            mTargetJoined = false;
            mTargetDevices = new JSONArray();
            mTargetsReady = true;
            paintTargets();
            return;
        }
        mTargetsReady = false;
        paintTargets();
        LemurXComputer.ensureNodeOnline((value, error) -> {
            if (gen != mTargetGen) return;
            if (value instanceof JSONObject) {
                JSONObject body = (JSONObject) value;
                LemurXComputer.noteServerAccount(getContext(), body);
                String host = body.optString("host", "");
                if (!host.isEmpty()) mControlledHost = host;
            }
            loadTargetDevices(gen);
        });
    }

    private void loadTargetDevices(int gen) {
        MuseApi.get().get("/api/tailnet/devices", (value, error) -> {
            if (gen != mTargetGen) return;
            if (!(value instanceof JSONObject)) return;
            JSONObject body = (JSONObject) value;
            JSONArray devices = body.optJSONArray("devices");
            mTargetDevices = devices == null ? new JSONArray() : devices;
            mTargetJoined = body.optBoolean("joined", false);
            mTargetsReady = true;
            for (int i = 0; i < mTargetDevices.length(); i++) {
                if (LemurXComputer.isControlledComputer(
                        mTargetDevices.optJSONObject(i), mControlledHost)) {
                    LemurXComputer.publishLocalHostIfNeeded(getContext());
                    break;
                }
            }
            paintTargets();
        });
    }

    private void paintTargets() {
        mTargetRow.removeAllViews();
        List<TargetChoice> choices = targetChoices();
        TargetChoice picked = choices.get(0);
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).selected) picked = choices.get(i);
        }
        addSelectedTarget(picked);
        if (mAskPlaced && !mListening) {
            mInput.setHint(inputHintRes());
            if (isComposing()) scheduleAskSheet();
        }
        mTargetBlock.setVisibility(View.GONE);
        if (mAskPlaced && mDeviceSheet.getVisibility() == View.VISIBLE) paintDeviceSheet();
    }

    /** 输入栏上只留当前设备。右边的切换打开列表，添加远程电脑在那个列表里。 */
    private void addSelectedTarget(TargetChoice picked) {
        Context context = getContext();
        LinearLayout chip = new LinearLayout(context);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(8), dp(4), dp(6), dp(4));
        chip.setBackground(MusePage.rounded(MusePage.bg(context), 14, context));
        ImageView image = new ImageView(context);
        image.setImageResource(picked.icon);
        image.setColorFilter(MusePage.text(context));
        if (picked.online == null) {
            chip.addView(image, new LinearLayout.LayoutParams(dp(15), dp(15)));
        } else {
            FrameLayout mark = new FrameLayout(context);
            mark.addView(image, new FrameLayout.LayoutParams(
                    dp(15), dp(15), Gravity.START | Gravity.CENTER_VERTICAL));
            FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(6), dp(6));
            dotLp.gravity = Gravity.BOTTOM | Gravity.END;
            mark.addView(presenceDot(context, picked.online), dotLp);
            chip.addView(mark, new LinearLayout.LayoutParams(dp(18), dp(16)));
        }
        TextView text = new TextView(context);
        text.setText(picked.label);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(dp(68));
        text.setTextColor(MusePage.text(context));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.setMarginStart(dp(5));
        chip.addView(text, textLp);
        ImageView swap = new ImageView(context);
        swap.setImageResource(R.drawable.lemurx_muse_ic_switch);
        swap.setColorFilter(MusePage.muted(context));
        swap.setContentDescription(context.getString(R.string.lemurx_muse_target_switch));
        swap.setClickable(true);
        swap.setFocusable(false);
        swap.setFocusableInTouchMode(false);
        swap.setPadding(dp(2), dp(2), dp(2), dp(2));
        swap.setOnClickListener(v -> {
            LemurXHaptics.tick(v);
            toggleDeviceSheet();
        });
        LinearLayout.LayoutParams swapLp = new LinearLayout.LayoutParams(dp(16), dp(16)); swapLp.setMarginStart(dp(3)); chip.addView(swap, swapLp);
        chip.setOnClickListener(v -> {
            LemurXHaptics.tick(v);
            toggleDeviceSheet();
        });
        chip.setContentDescription(presenceDescription(context, picked.description, picked.online));
        chip.setFocusable(false);
        chip.setFocusableInTouchMode(false);
        mTargetRow.addView(chip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 输入框底排的模型钮。点开后列出可选模型。 */
    private View modelChip(Context context) {
        LinearLayout chip = new LinearLayout(context);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(8), dp(6), dp(6), dp(6));
        chip.setBackground(MusePage.rounded(MusePage.bg(context), 14, context));
        ImageView image = new ImageView(context);
        image.setImageResource(R.drawable.lemurx_muse_ic_sparkles);
        image.setColorFilter(MusePage.text(context));
        chip.addView(image, new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView text = new TextView(context);
        text.setText(R.string.lemurx_muse_model);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(dp(72));
        text.setTextColor(MusePage.text(context));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.setMarginStart(dp(6));
        chip.addView(text, textLp);
        ImageView chevron = new ImageView(context);
        chevron.setImageResource(R.drawable.lemurx_muse_ic_chevron);
        chevron.setColorFilter(MusePage.muted(context));
        chevron.setRotation(90f);
        chip.addView(chevron, new LinearLayout.LayoutParams(dp(16), dp(16)));
        chip.setContentDescription(context.getString(R.string.lemurx_muse_model_sheet));
        chip.setClickable(true);
        chip.setFocusable(false);
        chip.setFocusableInTouchMode(false);
        chip.setOnClickListener(v -> {
            LemurXHaptics.tick(v);
            toggleModelSheet();
        });
        // 极小 weight：多出来的宽度几乎都给设备滚动区，不够时才一起被压窄。
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0.01f);
        lp.setMarginStart(dp(6));
        chip.setLayoutParams(lp);
        return chip;
    }

    /** 半透明遮罩。点空白处收起，卡片自己吃掉点击。 */
    private FrameLayout newPickerSheet(Context context, View.OnClickListener dismiss) {
        FrameLayout sheet = new FrameLayout(context);
        sheet.setVisibility(View.GONE);
        sheet.setClickable(true);
        sheet.setFocusable(false);
        sheet.setFocusableInTouchMode(false);
        sheet.setClipChildren(false);
        sheet.setOnClickListener(dismiss);
        return sheet;
    }

    private ScrollView newPickerScroll(Context context) {
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.setFocusable(false);
        scroll.setFocusableInTouchMode(false);
        scroll.setVerticalScrollBarEnabled(false);
        return scroll;
    }

    private LinearLayout newPickerRows(Context context) {
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, dp(4), 0, dp(6));
        return rows;
    }

    /**
     * 贴着输入栏的圆角卡片。行数少时跟着内容高，行数多时在遮罩里滚动，不盖住输入栏。
     */
    private LinearLayout pickerSheetCard(FrameLayout sheet, ScrollView scroll, int titleRes) {
        Context context = sheet.getContext();
        LinearLayout card = new LinearLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int mode = View.MeasureSpec.getMode(heightMeasureSpec);
                int max = View.MeasureSpec.getSize(heightMeasureSpec);
                ViewGroup.LayoutParams rowLp = scroll.getLayoutParams();
                rowLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                super.onMeasure(
                        widthMeasureSpec,
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                if (mode == View.MeasureSpec.UNSPECIFIED || getMeasuredHeight() <= max) return;
                int extra = getMeasuredHeight() - scroll.getMeasuredHeight();
                rowLp.height = Math.max(dp(120), max - extra);
                super.onMeasure(
                        widthMeasureSpec,
                        View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST));
            }
        };
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setOnClickListener(v -> {});
        stylePickerCard(card);
        card.setPadding(dp(8), dp(10), dp(8), dp(8));

        View handle = new View(context);
        handle.setBackground(MusePage.rounded(MusePage.handle(context), 2, context));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dp(12);
        card.addView(handle, handleLp);

        TextView title = new TextView(context);
        title.setText(titleRes);
        title.setTextColor(MusePage.text(context));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(12), 0, dp(12), dp(4));
        card.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        lp.setMargins(dp(12), dp(24), dp(12), dp(12));
        sheet.addView(card, lp);
        return card;
    }

    /** 设备、模型和附件共用的卡片底。 */
    private void stylePickerCard(LinearLayout card) {
        Context context = card.getContext();
        GradientDrawable face = MusePage.rounded(MusePage.bg(context), 28, context);
        face.setStroke(Math.max(1, dp(1)), MusePage.line(context));
        card.setBackground(face);
        card.setElevation(dp(18));
        card.setClipToOutline(true);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
    }

    private int pickerScrim(Context context) {
        return MusePage.night(context) ? 0xB3000000 : 0x73000000;
    }

    private void presentPicker(FrameLayout sheet, LinearLayout card) {
        hideKeyboard();
        sheet.animate().cancel();
        card.animate().cancel();
        sheet.setBackgroundColor(pickerScrim(getContext()));
        sheet.setAlpha(0f);
        card.setTranslationY(dp(28));
        sheet.setVisibility(View.VISIBLE);
        positionAskSheet();
        sheet.animate().alpha(1f).setDuration(160).start();
        card.animate().translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                .start();
        noteComposeSheets();
    }

    private void dismissPicker(FrameLayout sheet, LinearLayout card) {
        sheet.animate().cancel();
        card.animate().cancel();
        sheet.setAlpha(1f);
        card.setTranslationY(0f);
        sheet.setVisibility(View.GONE);
    }

    /**
     * 一条选项：圆角色块里的图标、标题、说明。选中的右侧标出正在使用，添加类选项给一个向右的箭头。
     */
    private View pickerRow(
            Context context,
            int icon,
            String title,
            String subtitle,
            boolean selected,
            boolean trailingChevron,
            @Nullable Boolean online,
            Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(10), dp(8));
        row.setClickable(true);
        row.setFocusable(false);
        row.setFocusableInTouchMode(false);

        FrameLayout well = new FrameLayout(context);
        well.setBackground(MusePage.rounded(
                selected ? MusePage.chipBg(context) : MusePage.surface(context), 14, context));
        ImageView image = new ImageView(context);
        image.setImageResource(icon);
        image.setColorFilter(selected ? MusePage.chipText(context) : MusePage.text(context));
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(20), dp(20));
        iconLp.gravity = Gravity.CENTER;
        well.addView(image, iconLp);
        if (online != null) {
            FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(9), dp(9));
            dotLp.gravity = Gravity.BOTTOM | Gravity.END;
            well.addView(presenceDot(context, online), dotLp);
            row.setContentDescription(presenceDescription(context, title, online));
        }
        row.addView(well, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(context);
        name.setText(title);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextColor(selected ? MusePage.accent(context) : MusePage.text(context));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        if (selected) name.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(name);
        if (!TextUtils.isEmpty(subtitle)) {
            TextView sub = new TextView(context);
            sub.setText(subtitle);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setTextColor(MusePage.muted(context));
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(2);
            text.addView(sub, subLp);
        }
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginStart(dp(12));
        textLp.setMarginEnd(dp(8));
        row.addView(text, textLp);

        if (selected) {
            TextView mark = new TextView(context);
            mark.setText(R.string.lemurx_muse_picker_current);
            mark.setTextColor(MusePage.chipText(context));
            mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            mark.setTypeface(Typeface.DEFAULT_BOLD);
            mark.setPadding(dp(8), dp(4), dp(8), dp(4));
            mark.setBackground(MusePage.rounded(MusePage.chipBg(context), 10, context));
            row.addView(mark);
        } else if (trailingChevron) {
            ImageView chevron = new ImageView(context);
            chevron.setImageResource(R.drawable.lemurx_muse_ic_chevron);
            chevron.setColorFilter(MusePage.muted(context));
            row.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));
        }
        row.setOnClickListener(v -> {
            LemurXHaptics.tick(v);
            action.run();
        });
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void addPickerDivider(LinearLayout host) {
        View line = new View(host.getContext());
        line.setBackgroundColor(MusePage.line(host.getContext()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.setMarginStart(dp(60));
        lp.setMarginEnd(dp(8));
        host.addView(line, lp);
    }

    private void toggleModelSheet() {
        if (!mAskPlaced) return;
        hideDeviceSheet();
        hideAttachSheet();
        if (mModelSheet.getVisibility() == View.VISIBLE) {
            hideModelSheet();
            return;
        }
        paintModelSheet();
        presentPicker(mModelSheet, mModelCard);
    }

    private void hideModelSheet() {
        if (!mAskPlaced || mModelSheet.getVisibility() == View.GONE) return;
        dismissPicker(mModelSheet, mModelCard);
        mModelRows.removeAllViews();
        noteComposeSheets();
    }

    private void paintModelSheet() {
        Context context = getContext();
        mModelRows.removeAllViews();
        mModelRows.addView(pickerRow(
                context,
                R.drawable.lemurx_muse_ic_sparkles,
                context.getString(R.string.lemurx_muse_model),
                context.getString(R.string.lemurx_muse_model_sub),
                true,
                false,
                null,
                this::hideModelSheet));
    }

    private void toggleDeviceSheet() {
        if (!mAskPlaced) return;
        hideModelSheet();
        hideAttachSheet();
        if (mDeviceSheet.getVisibility() == View.VISIBLE) {
            hideDeviceSheet();
            return;
        }
        paintDeviceSheet();
        presentPicker(mDeviceSheet, mDeviceCard);
    }

    private void hideDeviceSheet() {
        if (!mAskPlaced || mDeviceSheet.getVisibility() == View.GONE) return;
        dismissPicker(mDeviceSheet, mDeviceCard);
        mDeviceRows.removeAllViews();
        noteComposeSheets();
    }

    private void paintDeviceSheet() {
        Context context = getContext();
        mDeviceRows.removeAllViews();
        List<TargetChoice> choices = targetChoices();
        for (int i = 0; i < choices.size(); i++) {
            if (i > 0) addPickerDivider(mDeviceRows);
            addDeviceChoice(choices.get(i));
        }
        addPickerDivider(mDeviceRows);
        addDeviceChoice(new TargetChoice(
                context.getString(R.string.lemurx_muse_target_add_row),
                R.drawable.lemurx_muse_ic_plus,
                context.getString(R.string.lemurx_muse_target_add_computer_cd),
                false,
                null,
                true,
                context.getString(R.string.lemurx_muse_target_add_sub),
                null));
    }

    private void addDeviceChoice(TargetChoice choice) {
        Context context = getContext();
        mDeviceRows.addView(pickerRow(
                context,
                choice.icon,
                choice.label,
                choice.subtitle,
                choice.selected,
                choice.add,
                choice.online,
                () -> {
                    hideDeviceSheet();
                    if (mInput.hasFocus()) mInput.clearFocus();
                    if (choice.add) {
                        MusePage.showComputer(null, mThreadId);
                        return;
                    }
                    if (choice.ip == null) {
                        LemurXComputer.saveOperateTarget(context, LemurXComputer.TARGET_BROWSER);
                        paintTargets();
                        return;
                    }
                    selectComputer(choice.ip);
                }));
    }

    private List<TargetChoice> targetChoices() {
        Context context = getContext();
        String selected = LemurXComputer.operateTarget(context);
        boolean onBrowser = LemurXComputer.TARGET_BROWSER.equalsIgnoreCase(selected);
        boolean known = onBrowser;
        if (!onBrowser) {
            for (int i = 0; i < mTargetDevices.length(); i++) {
                JSONObject device = mTargetDevices.optJSONObject(i);
                if (device != null && device.optString("ip", "").equalsIgnoreCase(selected)
                        && LemurXComputer.isControlledComputer(device, mControlledHost)) {
                    known = true;
                }
            }
        }
        if (!known && mTargetsReady) {
            LemurXComputer.saveOperateTarget(context, LemurXComputer.TARGET_BROWSER);
            onBrowser = true;
        }
        List<TargetChoice> items = new ArrayList<>();
        items.add(new TargetChoice(
                context.getString(R.string.lemurx_muse_target_browser),
                R.drawable.lemurx_muse_ic_smartphone,
                context.getString(R.string.lemurx_muse_target_browser_cd),
                onBrowser,
                null,
                false,
                context.getString(R.string.lemurx_muse_target_browser_sub),
                null));
        for (int i = 0; i < mTargetDevices.length(); i++) {
            JSONObject device = mTargetDevices.optJSONObject(i);
            if (!LemurXComputer.isControlledComputer(device, mControlledHost)) continue;
            String ip = device.optString("ip", "");
            String name = device.optString("name", "");
            if (name.isEmpty()) name = ip;
            String os = device.optString("os", "").trim();
            String subtitle = os.isEmpty()
                    ? context.getString(R.string.lemurx_muse_target_computer_sub)
                    : context.getString(R.string.lemurx_muse_target_computer_os, os);
            boolean reachable = mTargetJoined && device.optBoolean("online", false);
            items.add(new TargetChoice(
                    name,
                    LemurXComputer.Page.deviceIcon(os, false),
                    context.getString(R.string.lemurx_muse_target_device_cd, name),
                    !onBrowser && ip.equalsIgnoreCase(selected),
                    ip,
                    false,
                    subtitle,
                    reachable));
        }
        return items;
    }

    private void addComposeChip(Context context, int label, Runnable action) {
        TextView chip = new TextView(context);
        chip.setText(label);
        chip.setSingleLine(true);
        chip.setTextColor(MusePage.text(context));
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(composeChipBackground(context));
        chip.setPadding(dp(12), dp(8), dp(12), dp(8));
        chip.setClickable(true);
        chip.setFocusable(false);
        chip.setFocusableInTouchMode(false);
        chip.setOnClickListener(v -> {
            LemurXHaptics.tick(v);
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        mComposeChips.addView(chip, lp);
    }

    /** 把当前输入交给 Aero。explain 和 lookup 互斥，都不是时原句发送。 */
    private void sendCompose(boolean explain, boolean lookup) {
        String typed = mInput.getText() == null ? "" : mInput.getText().toString().trim();
        if (typed.isEmpty()) return;
        Context context = getContext();
        if (explain) {
            sendAsk(context.getString(R.string.lemurx_muse_compose_explain_prompt, typed));
        } else if (lookup) {
            sendAsk(context.getString(R.string.lemurx_muse_compose_lookup_prompt, typed));
        } else {
            sendAsk(typed);
        }
    }

    private static final class TargetChoice {
        final String label;
        final int icon;
        final String description;
        final boolean selected;
        /** 空表示本机浏览器。 */
        final @Nullable String ip;
        final boolean add;
        final String subtitle;
        /** 远程电脑的在线状态。本机浏览器和「添加」没有这个点。 */
        final @Nullable Boolean online;

        TargetChoice(
                String label,
                int icon,
                String description,
                boolean selected,
                @Nullable String ip,
                boolean add,
                String subtitle,
                @Nullable Boolean online) {
            this.label = label;
            this.icon = icon;
            this.description = description;
            this.selected = selected;
            this.ip = ip;
            this.add = add;
            this.subtitle = subtitle;
            this.online = online;
        }
    }

    /** 绿点在线，红点离线。描一圈底色，压在图标角上时不会糊成一块。 */
    private View presenceDot(Context context, boolean online) {
        View dot = new View(context);
        GradientDrawable face = new GradientDrawable();
        face.setShape(GradientDrawable.OVAL);
        face.setColor(online ? 0xFF30D158 : 0xFFFF453A);
        face.setStroke(Math.max(1, dp(1)), MusePage.bg(context));
        dot.setBackground(face);
        dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return dot;
    }

    private String presenceDescription(Context context, String label, @Nullable Boolean online) {
        if (online == null) return label;
        return label + ", " + context.getString(online
                ? R.string.lemurx_muse_computer_online
                : R.string.lemurx_muse_computer_offline);
    }

    /** 点一台机器：记住它，并告诉服务端命令打到这台。本机浏览器不需要这一步。 */
    private void selectComputer(String ip) {
        Context context = getContext();
        LemurXComputer.saveHost(context, ip);
        LemurXComputer.saveOperateTarget(context, ip);
        paintTargets();
        JSONObject body = new JSONObject();
        try {
            body.put("host", ip);
            body.put("token", LemurXComputer.token(context));
        } catch (JSONException e) {
            return;
        }
        MuseApi.get().send("POST", "/api/tailnet/host", body, (value, error) -> {
            if (value instanceof JSONObject) {
                String accountToken = ((JSONObject) value).optString("deviceToken", "");
                if (!accountToken.isEmpty()) LemurXComputer.saveToken(context, accountToken);
            }
        });
    }

    // ---------------------------------------------------------------- 附件

    /** 这一轮要发给模型的一个附件。图片是 JPEG 的 Base64，摘录是已经截断的文本。 */
    private static final class Attach {
        final String name;
        String excerpt;
        final List<String> images;

        final String kind;

        /** 模型交回的 PDF / Word。正文是 Markdown，文件由手机排出来。 */
        final boolean generated;

        /** 对话工具这次在用户电脑上跑的命令。点预览看全文，复制不离开对话。 */
        final @Nullable String computerRunId;

        /** 排好的 PDF 或 docx。预览页在 images 里。 */
        @Nullable byte[] data;

        boolean rendering;

        Attach(String name, String excerpt, List<String> images, String kind) {
            this(name, excerpt, images, kind, false, null);
        }

        Attach(String name, String excerpt, List<String> images, String kind, boolean generated) {
            this(name, excerpt, images, kind, generated, null);
        }

        Attach(
                String name,
                String excerpt,
                List<String> images,
                String kind,
                boolean generated,
                @Nullable String computerRunId) {
            this.name = name;
            this.excerpt = excerpt;
            this.images = images;
            this.kind = kind;
            this.generated = generated;
            this.computerRunId = computerRunId;
        }
    }

    private void onAttachClicked() {
        LemurXHaptics.click(mAttach);
        if (mBusy || mAttachReading) return;
        if (mPending.size() >= 4) {
            showError(getContext().getString(R.string.lemurx_muse_attach_full));
            return;
        }
        toggleAttachSheet();
    }

    /** 回形针先上拉附件类型。选中后再打开对应的文件选择器。 */
    private void toggleAttachSheet() {
        if (!mAskPlaced) return;
        hideDeviceSheet();
        hideModelSheet();
        if (mAttachSheet.getVisibility() == View.VISIBLE) {
            hideAttachSheet();
            return;
        }
        paintAttachSheet();
        presentPicker(mAttachSheet, mAttachCard);
    }

    private void hideAttachSheet() {
        if (!mAskPlaced || mAttachSheet.getVisibility() == View.GONE) return;
        dismissPicker(mAttachSheet, mAttachCard);
        mAttachChoices.removeAllViews();
        noteComposeSheets();
    }

    private void paintAttachSheet() {
        Context context = getContext();
        mAttachChoices.removeAllViews();
        addAttachChoice(
                R.drawable.lemurx_muse_ic_file_image,
                R.string.lemurx_muse_attach_image,
                R.string.lemurx_muse_attach_image_hint,
                () -> openAttachPicker("image/*", null));
        addPickerDivider(mAttachChoices);
        addAttachChoice(
                R.drawable.lemurx_muse_ic_file_pdf,
                R.string.lemurx_muse_attach_pdf,
                R.string.lemurx_muse_attach_pdf_hint,
                () -> openAttachPicker("application/pdf", null));
        addPickerDivider(mAttachChoices);
        addAttachChoice(
                R.drawable.lemurx_muse_ic_file_doc,
                R.string.lemurx_muse_attach_doc,
                R.string.lemurx_muse_attach_doc_hint,
                () -> openAttachPicker(
                        "*/*",
                        new String[] {
                            "text/*",
                            "application/json",
                            "application/msword",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        }));
        addPickerDivider(mAttachChoices);
        addAttachChoice(
                R.drawable.lemurx_muse_ic_file_text,
                R.string.lemurx_muse_attach_any,
                R.string.lemurx_muse_attach_any_hint,
                () -> openAttachPicker("*/*", null));
    }

    private void addAttachChoice(int iconRes, int titleRes, int hintRes, Runnable open) {
        Context context = getContext();
        mAttachChoices.addView(pickerRow(
                context,
                iconRes,
                context.getString(titleRes),
                context.getString(hintRes),
                false,
                true,
                null,
                () -> {
                    hideAttachSheet();
                    open.run();
                }));
    }

    private void openAttachPicker(String type, @Nullable String[] mimeTypes) {
        WindowAndroid window = LemurXConsoleShell.windowAndroid();
        if (window == null) {
            showError(getContext().getString(R.string.lemurx_muse_attach_failed));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(type);
        if (mimeTypes != null) intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // 系统文件选择器返回时，Chromium 的界面线程池可能已经关掉。
        // 再丢给 ThreadUtils 会在 onActivityResult 里抛 RejectedExecutionException，整个浏览器退出。
        // 结果本来就在主线程上，直接读。
        boolean shown =
                window.showIntent(
                        intent,
                        (resultCode, data) -> deliverAttachResult(resultCode, data),
                        null);
        if (!shown) showError(getContext().getString(R.string.lemurx_muse_attach_failed));
    }

    /** 读文件的线程池在页面销毁时会关掉。选完文件再回来时换一个，避免结果送不出去。 */
    private void submitAttachRead(Runnable job) {
        try {
            mAttachRead.execute(job);
        } catch (RejectedExecutionException e) {
            mAttachRead = Executors.newSingleThreadExecutor();
            mAttachRead.execute(job);
        }
    }

    private void deliverAttachResult(int resultCode, @Nullable Intent data) {
        Runnable job =
                () -> {
                    try {
                        onAttachResult(resultCode, data);
                    } catch (RuntimeException e) {
                        Log.i(TAG, "attach result: %s", e.getMessage());
                        mAttachReading = false;
                        if (mAttach != null) mAttach.setAlpha(mBusy ? 0.35f : 1f);
                    }
                };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            job.run();
        } else {
            new Handler(Looper.getMainLooper()).post(job);
        }
    }

    private void onAttachResult(int resultCode, @Nullable Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || mAttachReading) return;
        int room = 4 - mPending.size();
        if (room <= 0) {
            showError(getContext().getString(R.string.lemurx_muse_attach_full));
            return;
        }
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount() && uris.size() < room; i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
            if (clip.getItemCount() > uris.size()) {
                showError(getContext().getString(R.string.lemurx_muse_attach_full));
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        int budget = 2;
        for (Attach file : mPending) budget -= file.images.size();
        if (budget < 0) budget = 0;
        mAttachReading = true;
        mAttach.setAlpha(0.35f);
        final int gen = mAttachGen;
        final int imageBudget = budget;
        final Context app = getContext().getApplicationContext();
        submitAttachRead(
                () -> {
                    List<Attach> found = new ArrayList<>();
                    boolean failed = false;
                    int left = imageBudget;
                    for (Uri uri : uris) {
                        try {
                            Attach file = readAttach(app, uri, left);
                            left -= file.images.size();
                            found.add(file);
                        } catch (Exception e) {
                            Log.i(TAG, "attach: %s", e.getMessage());
                            failed = true;
                        }
                    }
                    final boolean showFail = failed;
                    ThreadUtils.runOnUiThread(
                            () -> {
                                if (gen != mAttachGen) return;
                                mAttachReading = false;
                                mAttach.setAlpha(mBusy ? 0.35f : 1f);
                                for (Attach file : found) {
                                    if (mPending.size() >= 4) break;
                                    mPending.add(file);
                                }
                                renderAttach();
                                if (showFail) {
                                    showError(getContext().getString(R.string.lemurx_muse_attach_failed));
                                }
                            });
                });
    }

    private void renderAttach() {
        mAttachRow.removeAllViews();
        if (mPending.isEmpty()) {
            mAttachScroll.setVisibility(View.GONE);
            return;
        }
        mAttachScroll.setVisibility(View.VISIBLE);
        Context context = getContext();
        for (int i = 0; i < mPending.size(); i++) {
            Attach file = mPending.get(i);
            LinearLayout chip = new LinearLayout(context);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackground(MusePage.rounded(MusePage.surface(context), 14, context));
            chip.setPadding(dp(10), dp(6), dp(6), dp(6));
            ImageView icon = new ImageView(context);
            icon.setImageResource(fileIcon(file.kind));
            icon.setColorFilter(MusePage.text(context), PorterDuff.Mode.SRC_IN);
            chip.addView(icon, new LinearLayout.LayoutParams(dp(16), dp(16)));
            TextView name = new TextView(context);
            name.setText(file.name);
            name.setTextColor(MusePage.text(context));
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setMaxWidth(dp(180));
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nameLp.setMarginStart(dp(6));
            chip.addView(name, nameLp);
            View.OnClickListener open =
                    v -> {
                        try {
                            openAttachment(file);
                        } catch (RuntimeException e) {
                            Log.i(TAG, "preview: %s", e.getMessage());
                        }
                    };
            chip.setOnClickListener(open);
            icon.setOnClickListener(open);
            name.setOnClickListener(open);
            TextView remove = new TextView(context);
            remove.setText("×");
            remove.setTextColor(MusePage.muted(context));
            remove.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            remove.setPadding(dp(8), 0, dp(6), 0);
            remove.setContentDescription(context.getString(R.string.lemurx_muse_attach_remove));
            final int index = i;
            remove.setOnClickListener(
                    v -> {
                        LemurXHaptics.tick(v);
                        if (index < mPending.size()) mPending.remove(index);
                        renderAttach();
                    });
            chip.addView(remove);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(dp(8));
            mAttachRow.addView(chip, lp);
        }
    }

    /** 气泡里只放用户打的字。摘录留在 {@link #outbound}，不写进可见文本。 */
    private String composeOutgoing(String typed, List<Attach> files) {
        StringBuilder out = new StringBuilder(typed);
        if (files.isEmpty()) return out.toString().trim();
        if (out.length() > 0) out.append("\n\n");
        StringBuilder names = new StringBuilder();
        for (Attach file : files) {
            if (names.length() > 0) names.append(", ");
            names.append(file.name);
        }
        out.append(getContext().getString(R.string.lemurx_muse_attached, names.toString()));
        out.append('\n');
        out.append(getContext().getString(R.string.lemurx_muse_attach_data));
        for (Attach file : files) {
            if (file.excerpt.isEmpty()) continue;
            out.append("\n\n").append(file.name).append(":\n").append(file.excerpt);
        }
        return out.toString().trim();
    }

    private static Attach readAttach(Context context, Uri uri, int imageBudget) throws Exception {
        String name = displayName(context, uri);
        String mime = context.getContentResolver().getType(uri);
        if (mime == null) mime = "";
        String lower = name.toLowerCase(Locale.US);
        if (mime.startsWith("image/") || imageExtension(lower)) {
            if (imageBudget <= 0) return new Attach(name, "", List.of(), "image");
            byte[] bytes = readCapped(context, uri, 12_000_000);
            String jpeg = jpegOf(bytes);
            if (jpeg == null) throw new IllegalStateException("image");
            return new Attach(name, "", List.of(jpeg), "image");
        }
        if ("application/pdf".equals(mime) || lower.endsWith(".pdf")) {
            return new Attach(name, "", pdfPages(context, uri, imageBudget), "pdf");
        }
        if (mime.startsWith("text/")
                || "application/json".equals(mime)
                || "application/xml".equals(mime)
                || lower.endsWith(".txt")
                || lower.endsWith(".md")
                || lower.endsWith(".csv")
                || lower.endsWith(".json")) {
            String text = new String(readCapped(context, uri, 200_000), StandardCharsets.UTF_8);
            if (text.indexOf('\uFFFD') >= 0) {
                text = new String(readCapped(context, uri, 200_000), StandardCharsets.ISO_8859_1);
            }
            return new Attach(name, clip(text, 12_000), List.of(), "text");
        }
        if (lower.endsWith(".docx")
                || mime.contains("wordprocessingml")) {
            return new Attach(name, clip(docxText(context, uri), 12_000), List.of(), "doc");
        }
        throw new IllegalStateException("unsupported");
    }

    /** 相册有时不给 image/*，截图仍按扩展名当图片读。 */
    private static boolean imageExtension(String lower) {
        return lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".webp")
                || lower.endsWith(".gif")
                || lower.endsWith(".heic")
                || lower.endsWith(".heif");
    }

    private static String displayName(Context context, Uri uri) {
        String name = "";
        try (Cursor cursor = context.getContentResolver()
                .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception e) {
            name = "";
        }
        if (name == null) name = "";
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.length() > 80) name = name.substring(0, 80);
        return name.isEmpty() ? "file" : name;
    }

    private static byte[] readCapped(Context context, Uri uri, int max) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("unreadable");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (out.size() + n > max) throw new IllegalStateException("too big");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static @Nullable String jpegOf(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        if (bitmap == null) return null;
        try {
            return jpeg(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    private static List<String> pdfPages(Context context, Uri uri, int budget) throws Exception {
        if (budget <= 0) return List.of();
        List<String> pages = new ArrayList<>();
        ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r");
        if (fd == null) throw new IllegalStateException("pdf");
        // PdfRenderer 会关掉这个 fd，所以不要再关一次。
        try (PdfRenderer renderer = new PdfRenderer(fd)) {
                int count = Math.min(renderer.getPageCount(), budget);
                for (int i = 0; i < count; i++) {
                    PdfRenderer.Page page = renderer.openPage(i);
                    try {
                        int edge = Math.max(page.getWidth(), page.getHeight());
                        float scale = edge > 1280 ? 1280f / edge : 1f;
                        int w = Math.max(1, Math.round(page.getWidth() * scale));
                        int h = Math.max(1, Math.round(page.getHeight() * scale));
                        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(Color.WHITE);
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        try {
                            String jpeg = jpeg(bitmap);
                            if (jpeg != null) pages.add(jpeg);
                        } finally {
                            bitmap.recycle();
                        }
                    } finally {
                        page.close();
                    }
                }
        } catch (Exception e) {
            try {
                fd.close();
            } catch (Exception ignored) {
                // 渲染器没接住 fd 时补一刀；已经关掉就忽略。
            }
            throw e;
        }
        return pages;
    }

    private static @Nullable String jpeg(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int quality = 72;
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        while (out.size() > 280_000 && quality > 40) {
            quality -= 8;
            out.reset();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        }
        if (out.size() == 0) return null;
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private static String docxText(Context context, Uri uri) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("docx");
            try (ZipInputStream zip = new ZipInputStream(in)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!"word/document.xml".equals(entry.getName())) continue;
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zip.read(buf)) >= 0) {
                        if (out.size() + n > 400_000) break;
                        out.write(buf, 0, n);
                    }
                    String xml = new String(out.toByteArray(), StandardCharsets.UTF_8);
                    xml = xml.replaceAll("</w:p>", "\n");
                    xml = xml.replaceAll("<[^>]+>", "");
                    xml = xml.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
                    return xml;
                }
            }
        }
        throw new IllegalStateException("docx");
    }

    private static String clip(String text, int max) {
        String cleaned = text.replace("\u0000", "").trim();
        if (cleaned.length() <= max) return cleaned;
        return cleaned.substring(0, max);
    }

    /**
     * 把标签页上记着的网页截图挂到最后一条用户消息上（只在这次请求里，不进本地历史）：
     * content 变成 [文字, 图片] 的多模态数组。没有截图就原样返回。
     */
    private JSONArray withTabContext(JSONArray messages) {
        String shot = LemurXConsoleShell.tabScreenshot(mTab);
        if (shot == null || messages.length() == 0) return messages;
        try {
            JSONObject last = messages.getJSONObject(messages.length() - 1);
            if (!"user".equals(last.optString("role"))) return messages;
            JSONObject text = new JSONObject();
            text.put("type", "text");
            text.put("text", last.optString("content", ""));
            JSONObject note = new JSONObject();
            note.put("type", "text");
            note.put(
                    "text",
                    "Screenshot of the web page open in this tab. This is the page, not a file the user attached.");
            JSONObject source = new JSONObject();
            source.put("type", "data");
            source.put("mimeType", "image/jpeg");
            source.put("value", shot);
            JSONObject image = new JSONObject();
            image.put("type", "image");
            image.put("source", source);
            JSONObject copy = new JSONObject(last.toString());
            copy.put("content", new JSONArray().put(text).put(note).put(image));
            JSONArray result = new JSONArray();
            for (int i = 0; i < messages.length() - 1; i++) result.put(messages.get(i));
            result.put(copy);
            return result;
        } catch (JSONException e) {
            return messages;
        }
    }

    /**
     * 用户附的图片接在网页截图后面，这一轮和后面接着问的轮次都带上。
     * 只留最新的两张，网页截图不算在里面。文本摘录已经写在消息正文里，这里补图片。
     */
    private JSONArray withAttachments(JSONArray messages, List<Attach> files) {
        List<String> images = recentUserImages();
        if (images.isEmpty() && files != null) {
            for (Attach file : files) images.addAll(file.images);
            if (images.size() > 2) images = new ArrayList<>(images.subList(images.size() - 2, images.size()));
        }
        if (images.isEmpty() || messages.length() == 0) return messages;
        try {
            JSONObject last = messages.getJSONObject(messages.length() - 1);
            if (!"user".equals(last.optString("role"))) return messages;
            JSONArray parts;
            Object content = last.opt("content");
            if (content instanceof JSONArray) {
                parts = (JSONArray) content;
            } else {
                JSONObject text = new JSONObject();
                text.put("type", "text");
                text.put("text", last.optString("content", ""));
                parts = new JSONArray().put(text);
            }
            JSONObject note = new JSONObject();
            note.put("type", "text");
            note.put(
                    "text",
                    "Pictures the user attached, oldest first. Read the text and details in them. "
                            + "These are files the user sent, not the web page open in this tab.");
            parts.put(note);
            for (String image : images) {
                if (!partHasImage(parts, image)) parts.put(imagePart(image));
            }
            last.put("content", parts);
            return messages;
        } catch (JSONException e) {
            return messages;
        }
    }

    /** 这段对话里用户自己选的图，按时间从旧到新，只要最新两张。模型交回的预览页不算。 */
    private List<String> recentUserImages() {
        List<String> images = new ArrayList<>();
        for (Message message : mMessages) {
            if (!"user".equals(message.role) || message.uiOnly) continue;
            for (Attach file : message.files) {
                if (file.generated) continue;
                images.addAll(file.images);
            }
        }
        if (images.size() <= 2) return images;
        return new ArrayList<>(images.subList(images.size() - 2, images.size()));
    }

    private static boolean partHasImage(JSONArray parts, String jpeg) {
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null || !"image".equals(part.optString("type"))) continue;
            JSONObject source = part.optJSONObject("source");
            if (source != null && jpeg.equals(source.optString("value"))) return true;
        }
        return false;
    }

    private static JSONObject imagePart(String jpegBase64) throws JSONException {
        JSONObject source = new JSONObject();
        source.put("type", "data");
        source.put("mimeType", "image/jpeg");
        source.put("value", jpegBase64);
        JSONObject image = new JSONObject();
        image.put("type", "image");
        image.put("source", source);
        return image;
    }

    // ---------------------------------------------------------------- 持久化

    private JSONArray toJson() {
        JSONArray array = new JSONArray();
        for (Message m : mMessages) {
            if (m.uiOnly) continue;
            String content = storedContent(m);
            if (TextUtils.isEmpty(content)) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("role", m.role);
                o.put("content", content);
                array.put(o);
            } catch (JSONException e) {
                // 不会发生：都是字符串
            }
        }
        return array;
    }

    /** 这一轮发给模型的消息。最后一条用户消息带上摘录，气泡和存档里仍然只有图标和文件名。 */
    private JSONArray outbound(List<Attach> files) {
        JSONArray messages = toJson();
        if (files.isEmpty() || messages.length() == 0) return messages;
        try {
            JSONObject last = messages.getJSONObject(messages.length() - 1);
            if (!"user".equals(last.optString("role"))) return messages;
            String typed = "";
            for (int i = mMessages.size() - 1; i >= 0; i--) {
                Message message = mMessages.get(i);
                if ("user".equals(message.role)) {
                    typed = message.text;
                    break;
                }
            }
            last.put("content", composeOutgoing(typed, files));
        } catch (JSONException e) {
            return messages;
        }
        return messages;
    }

    private static final Pattern FILE_MARK =
            Pattern.compile("\\[\\[aero-file:(image|pdf|text|doc|computer):([^\\]]+)\\]\\]");

    /** 存档带上文本正文，重新打开还能预览。发给模型的那份只留文件名。 */
    private JSONArray archiveJson() {
        JSONArray array = new JSONArray();
        for (Message m : mMessages) {
            String content = archiveContent(m);
            if (TextUtils.isEmpty(content)) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("role", m.role);
                o.put("content", content);
                array.put(o);
            } catch (JSONException e) {
                // 不会发生：都是字符串
            }
        }
        return array;
    }

    private static String archiveContent(Message message) {
        StringBuilder out = new StringBuilder(message.text == null ? "" : message.text.trim());
        for (Attach file : message.files) {
            String name = file.name.replace("]", " ").replace("\n", " ").trim();
            if (name.isEmpty()) name = "file";
            if (out.length() > 0) out.append('\n');
            out.append("[[aero-file:").append(file.kind).append(':').append(name).append("]]");
            if (file.generated) out.append("\n[[aero-office]]");
            if ("computer".equals(file.kind)) {
                // 卡片要活过进程重启：runId + 短预览进存档；完整输出只在本机 LemurXVault。
                String runId = file.computerRunId == null ? "" : file.computerRunId;
                LemurXComputer.Run run = runId.isEmpty() ? null : LemurXComputer.getRun(runId);
                JSONObject meta = new JSONObject();
                try {
                    meta.put("runId", runId);
                    String command = run != null && run.command != null && !run.command.isEmpty()
                            ? run.command
                            : file.name;
                    meta.put("command", command);
                    String preview = file.excerpt == null ? "" : file.excerpt;
                    if (preview.length() > 240) preview = preview.substring(preview.length() - 240);
                    meta.put("preview", preview);
                    meta.put("ok", run == null || run.ok);
                    meta.put("done", run == null || run.done);
                    if (run != null && run.error != null) meta.put("error", run.error);
                } catch (JSONException e) {
                    // ignore
                }
                String body = Base64.encodeToString(
                        meta.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                out.append("\n[[aero-body:").append(body).append("]]");
                continue;
            }
            if (!file.excerpt.isEmpty()) {
                String body = Base64.encodeToString(
                        file.excerpt.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                out.append("\n[[aero-body:").append(body).append("]]");
            }
        }
        for (String step : message.steps) {
    String enc = Base64.encodeToString(step.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    if (out.length() > 0) out.append('\n');
    out.append("[[aero-step:").append(enc).append("]]");
}
        String body = out.toString().trim();
        if (message.uiOnly && !body.isEmpty()) return "[[aero-ui]]\n" + body;
        return body;
    }

    private static final Pattern STEP_MARK = Pattern.compile("\\[\\[aero-step:([A-Za-z0-9+/=]+)\\]\\]");
    private static final Pattern BODY_MARK = Pattern.compile("\\[\\[aero-body:([A-Za-z0-9+/=]+)\\]\\]");

    /** 发给模型的历史只留种类和文件名，正文留在存档里供预览。 */
    private static String storedContent(Message message) {
        StringBuilder out = new StringBuilder(message.text == null ? "" : message.text.trim());
        for (Attach file : message.files) {
            if ("computer".equals(file.kind)) continue;
            String name = file.name.replace("]", " ").replace("\n", " ").trim();
            if (name.isEmpty()) name = "file";
            if (out.length() > 0) out.append('\n');
            out.append("[[aero-file:").append(file.kind).append(':').append(name).append("]]");
        }
        return out.toString().trim();
    }

    private static Message decodeStored(String id, String role, String content) {
        boolean uiOnly = content.startsWith("[[aero-ui]]");
        if (uiOnly) content = content.substring("[[aero-ui]]".length()).trim();
        List<String> steps = new ArrayList<>();
        if (content.contains("[[aero-step:")) {
            Matcher sm = STEP_MARK.matcher(content);
            while (sm.find()) {
                String b = sm.group(1);
                try {
                    steps.add(new String(Base64.decode(b, Base64.DEFAULT), StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
            }
            content = STEP_MARK.matcher(content).replaceAll("").trim();
        }

        if (!content.contains("[[aero-file:")) {
            Message plain = new Message(id, role, content);
            plain.uiOnly = uiOnly;
            plain.steps.addAll(steps);
            return plain;
        }
        List<Attach> files = new ArrayList<>();
        Matcher matcher = FILE_MARK.matcher(content);
        while (matcher.find()) {
            String kind = matcher.group(1);
            String name = matcher.group(2);
            if (kind == null || name == null) continue;
            String tail = content.substring(matcher.end());
            boolean generated = tail.startsWith("\n[[aero-office]]") || tail.startsWith("[[aero-office]]");
            if (generated) {
                tail = tail.startsWith("\n") ? tail.substring(1) : tail;
                tail = tail.substring("[[aero-office]]".length());
            }
            String excerpt = "";
            String bodyText = "";
            Matcher body = BODY_MARK.matcher(tail);
            if (body.lookingAt()) {
                String encoded = body.group(1);
                if (encoded != null) {
                    try {
                        bodyText = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
                        excerpt = bodyText;
                    } catch (IllegalArgumentException e) {
                        excerpt = "";
                    }
                }
            }
            if ("computer".equals(kind)) {
                String runId = "";
                boolean ok = true;
                boolean done = true;
                @Nullable String error = null;
                try {
                    JSONObject meta = new JSONObject(bodyText);
                    runId = meta.optString("runId", "");
                    String savedCommand = meta.optString("command", "");
                    if (!savedCommand.isEmpty()) name = savedCommand;
                    excerpt = meta.optString("preview", excerpt);
                    ok = meta.optBoolean("ok", true);
                    done = meta.optBoolean("done", true);
                    error = meta.has("error") ? meta.optString("error") : null;
                    // 旧存档里若仍带 output，忽略：全文只从本机 vault 再水合。
                } catch (JSONException e) {
                    // 旧格式或坏数据：没有 runId 就不做卡片。
                }
                if (runId.isEmpty()) continue;
                if (excerpt == null) excerpt = "";
                LemurXComputer.hydrate(runId, name, null, ok, done, error, null, null);
                files.add(new Attach(name, excerpt, List.of(), "computer", false, runId));
                continue;
            }
            files.add(
                    new Attach(
                            name,
                            excerpt,
                            new ArrayList<>(),
                            kind,
                            generated));
        }
        String visible =
                BODY_MARK.matcher(FILE_MARK.matcher(content).replaceAll(""))
                        .replaceAll("")
                        .replace("[[aero-office]]", "")
                        .replace("[[aero-ui]]", "")
                        .trim();
        Message loaded = new Message(id, role, visible, files);
        loaded.uiOnly = uiOnly;
        loaded.steps.addAll(steps);
        return loaded;
    }

    /** 用户气泡只放写下的字。文件单独成卡片，不嵌进这段文字里。 */
    private void paintUser(TextView view, Message message) {
        view.setText(message.text);
        view.setVisibility(TextUtils.isEmpty(message.text) ? View.GONE : View.VISIBLE);
    }

    /**
     * 用户这句话值得回应时，气泡右下角留一个很小的表情。
     * 只挑有语气的短句，长任务和贴进来的大段文字不标，免得每句都在点头。
     */
    private static String moodFor(String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty()) return "";
        String lower = raw.toLowerCase(Locale.ROOT);
        boolean warm = hasAny(raw, lower, "谢谢", "感谢", "喜欢", "爱你", "thanks", "thank you", "love");
        boolean laugh = hasAny(raw, lower, "哈哈", "笑死", "好笑", "hhh", "lol");
        boolean wave = hasAny(raw, lower, "你好", "您好", "早上好", "晚安", "嗨", "hello", "hi ", "hey");
        if (lower.equals("hi") || lower.equals("hey")) wave = true;
        boolean with = hasAny(raw, lower, "抱歉", "对不起", "难过", "崩溃", "好累", "好烦", "sorry", "tired");
        boolean cheer = hasAny(raw, lower, "搞定", "成功了", "做到了", "完成了", "赢了");
        boolean spark = hasAny(raw, lower, "太棒", "太好了", "厉害", "不错", "漂亮", "完美", "great", "wow", "nice");
        if (warm) return "❤️";
        if (laugh) return "😄";
        if (wave) return "👋";
        if (with) return "🤝";
        if (cheer) return "🎉";
        if (spark) return "✨";
        if (raw.length() > 40) return "";
        boolean ask = raw.indexOf('?') >= 0
                || raw.indexOf('？') >= 0
                || raw.endsWith("吗")
                || raw.endsWith("呢")
                || hasAny(raw, lower, "什么", "怎么", "为什么", "如何", "what", "how", "why");
        if (ask) return "👍";
        if (raw.equals("好")
                || raw.equals("好的")
                || raw.equals("好呀")
                || raw.equals("好啊")
                || raw.equals("嗯")
                || raw.equals("行")
                || raw.equals("收到")
                || lower.equals("ok")
                || lower.equals("okay")
                || lower.equals("yes")) {
            return "👍";
        }
        return "";
    }

    private static boolean hasAny(String raw, String lower, String... keys) {
        for (String key : keys) {
            if (key.isEmpty()) continue;
            if (key.codePointAt(0) < 128) {
                if (lower.contains(key)) return true;
            } else if (raw.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 反应压在气泡右下角，底色和气泡不一样，所以看得出是 Aero 贴上去的，不是这句话的一部分。
     * 新出现的会晚一点再弹出来，并震一下。翻旧对话时直接摆上。
     */
    private View moodBadge(String glyph) {
        Context context = getContext();
        TextView mark = new TextView(context);
        mark.setText(glyph);
        mark.setGravity(Gravity.CENTER);
        mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mark.setBackground(MusePage.rounded(MusePage.accent(context), 15, context));
        mark.setContentDescription(context.getString(moodLabel(glyph)));
        if (mBatch) return mark;
        mark.setAlpha(0f);
        mark.setScaleX(0.6f);
        mark.setScaleY(0.6f);
        postDelayed(
                () -> {
                    mark.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
                    LemurXHaptics.pulse(mark);
                },
                420);
        return mark;
    }

    private static int moodLabel(String glyph) {
        if ("❤️".equals(glyph)) return R.string.lemurx_mood_love;
        if ("😄".equals(glyph)) return R.string.lemurx_mood_laugh;
        if ("👋".equals(glyph)) return R.string.lemurx_mood_wave;
        if ("🤝".equals(glyph)) return R.string.lemurx_mood_with;
        if ("✨".equals(glyph)) return R.string.lemurx_mood_spark;
        if ("🎉".equals(glyph)) return R.string.lemurx_mood_cheer;
        return R.string.lemurx_mood_like;
    }

    /** 助手回复下面：复制正文，以及发到社区。 */
    private View messageActions(Message message) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(copyChip(message, Gravity.START));
        row.addView(shareChip(message));
        return row;
    }

    /** 把这条对话里看得见的文字放进剪贴板。 */
    private TextView copyChip(Message message, int gravity) {
        TextView copy = new TextView(getContext());
        copy.setText(R.string.lemurx_muse_command_copy);
        copy.setTextColor(MusePage.muted(getContext()));
        copy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        copy.setPadding(dp(2), 0, dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = gravity;
        copy.setLayoutParams(lp);
        copy.setOnClickListener(v -> copyPlain(plainMessage(message), copy));
        return copy;
    }

    /** 复制排好的正文，不带还在打的光标。没有排版时退回原文。 */
    private String plainMessage(Message message) {
        if (message.body != null) {
            StringBuilder out = new StringBuilder();
            appendVisibleText(message.body, out);
            String text = out.toString().replace(" ▍", "").trim();
            if (!text.isEmpty()) return text;
        }
        String raw = message.text == null ? "" : message.text.replace(" ▍", "").trim();
        return raw;
    }

    private void appendVisibleText(View view, StringBuilder out) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) {
                if (out.length() > 0) out.append('\n');
                out.append(text);
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) appendVisibleText(group.getChildAt(i), out);
        }
    }

    private View shareChip(Message message) {
        TextView share = new TextView(getContext());
        share.setText(R.string.lemurx_muse_community_share);
        share.setTextColor(MusePage.muted(getContext()));
        share.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        share.setPadding(dp(2), 0, dp(12), dp(8));
        share.setOnClickListener(v -> MuseCommunityView.shareNote(message.text));
        return share;
    }

    /**
     * 命令卡片放在这条回复的正文前面，先看到跑了什么，再读 Aero 的话。
     * 别的附件仍挂在正文下面。
     */
    private void bindFiles(Message message) {
        LinearLayout row = message.row;
        if (row == null) return;
        boolean user = "user".equals(message.role);
        List<Attach> commands = new ArrayList<>();
        List<Attach> rest = new ArrayList<>();
        for (Attach file : message.files) {
            if (!user && "computer".equals(file.kind)) commands.add(file);
            else rest.add(file);
        }
        placeFileBox(message, "aero-commands", commands, !user);
        placeFileBox(message, "aero-files", rest, false);
    }

    /** beforeReply 时插到正文前面；否则留在这一行的最后。 */
    private void placeFileBox(Message message, String tag, List<Attach> files, boolean beforeReply) {
        LinearLayout row = message.row;
        if (row == null) return;
        View existing = row.findViewWithTag(tag);
        if (files.isEmpty()) {
            if (existing != null) row.removeView(existing);
            return;
        }
        LinearLayout box;
        if (existing instanceof LinearLayout) {
            box = (LinearLayout) existing;
            box.removeAllViews();
        } else {
            box = new LinearLayout(getContext());
            box.setTag(tag);
            box.setOrientation(LinearLayout.VERTICAL);
        }
        boolean user = "user".equals(message.role);
        for (Attach file : files) box.addView(fileCard(file, user));
        if (box.getParent() == row) row.removeView(box);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (beforeReply && message.body != null && row.indexOfChild(message.body) >= 0) {
            row.addView(box, row.indexOfChild(message.body), lp);
        } else {
            row.addView(box, lp);
        }
    }

    /** 电脑没跑成时，这句英文已经在命令卡片里说明，不再写进正文。 */
    private String replyBesideCommand(Message message, String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        boolean computer = false;
        for (Attach file : message.files) {
            if ("computer".equals(file.kind)) {
                computer = true;
                break;
            }
        }
        if (!computer) return markdown;
        String shown = markdown.replace("The computer did not run the command", "");
        return shown.replaceAll("[ \\t]*\\n[ \\t]*\\n(?:[ \\t]*\\n)+", "\n\n").trim();
    }

    private View fileCard(Attach file, boolean user) {
        if ("computer".equals(file.kind)) return commandCard(file);
        Context context = getContext();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(MusePage.rounded(MusePage.surface(context), 16, context));
        card.setOnClickListener(v -> openAttachment(file));
        ImageView icon = new ImageView(context);
        icon.setImageResource(fileIcon(file.kind));
        icon.setColorFilter(MusePage.text(context), PorterDuff.Mode.SRC_IN);
        card.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(context);
        name.setText(file.name);
        name.setTextColor(MusePage.text(context));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView kind = new TextView(context);
        kind.setText(fileKindLabel(file.kind));
        kind.setTextColor(MusePage.muted(context));
        kind.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        kind.setMaxLines(2);
        kind.setEllipsize(TextUtils.TruncateAt.START);
        texts.addView(name);
        texts.addView(kind);
        TextView share = new TextView(context);
        share.setText(R.string.lemurx_muse_community_share);
        share.setTextColor(MusePage.muted(context));
        share.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        share.setPadding(dp(10), dp(4), 0, dp(4));
        share.setOnClickListener(
                v -> MuseCommunityView.shareWork(file.name, file.kind, file.excerpt));
        texts.addView(share);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMarginStart(dp(10));
        card.addView(texts, tlp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        if (user) {
            lp.gravity = Gravity.END;
            lp.setMarginStart(dp(48));
        } else {
            lp.setMarginEnd(dp(48));
        }
        card.setLayoutParams(lp);
        return card;
    }

    /** 对话里的一条命令：等宽、可选中，复制不离开对话，预览再看全文。 */
        static String humanizeCommand(Context context, @Nullable String command) {
        boolean zh = Locale.getDefault().getLanguage().startsWith("zh");
        if (command == null || command.trim().isEmpty()) {
            return zh ? "执行终端指令" : "Run terminal command";
        }
        String cmd = command.trim();
        String lower = cmd.toLowerCase(Locale.US);
        if (lower.contains("git status")) return zh ? "检查代码仓库状态" : "Check git status";
        if (lower.contains("git diff")) return zh ? "对比代码修改与差异" : "View code diff";
        if (lower.contains("git log")) return zh ? "查看代码提交记录" : "View git commit log";
        if (lower.contains("git branch")) return zh ? "查看代码分支" : "View git branches";
        if (lower.contains("git commit") || lower.contains("git add")) return zh ? "暂存或提交代码修改" : "Stage or commit changes";
        if (lower.contains("git checkout") || lower.contains("git switch")) return zh ? "切换代码分支" : "Switch git branch";
        if (lower.contains("git pull") || lower.contains("git fetch")) return zh ? "拉取最新代码" : "Pull latest code";
        if (lower.contains("git push")) return zh ? "推送代码到远程仓库" : "Push code to remote";
        if (lower.startsWith("git ")) return zh ? "执行 Git 操作" : "Execute git operation";
        if (lower.contains("grep ") || lower.contains("rg ")) return zh ? "检索代码关键字" : "Search code in project";
        if (lower.contains("find ")) return zh ? "搜索工程文件路径" : "Find files in project";
        if (lower.contains("cat ") || lower.contains("sed ") || lower.contains("head ")) return zh ? "读取与分析文件内容" : "Read and analyze file";
        if (lower.contains("ls ") || lower.equals("ls") || lower.contains("tree ")) return zh ? "浏览目录与文件列表" : "List files and directories";
        if (lower.contains("ps ") || lower.contains("top")) return zh ? "检查系统运行进程" : "Inspect running processes";
        if (lower.contains("df ") || lower.contains("free ")) return zh ? "检查系统资源与负载" : "Check system resources";
        if (lower.contains("ninja") || lower.contains("gradle") || lower.contains("make")) return zh ? "编译构建工程项目" : "Build and compile project";
        if (lower.contains("npm ") || lower.contains("pnpm ") || lower.contains("yarn ") || lower.contains("node ")) return zh ? "运行 Node 脚本与服务" : "Run Node scripts";
        if (lower.contains("python") || lower.contains("pytest")) return zh ? "运行 Python 脚本或测试" : "Run Python script";
        if (lower.contains("mkdir ") || lower.contains("cp ") || lower.contains("mv ")) return zh ? "整理与管理文件" : "Manage files";
        return zh ? "在远程电脑上执行操作" : "Execute on remote computer";
    }

    private boolean commandFailed(Attach file) {
        if (file.computerRunId != null) {
            LemurXComputer.Run run = LemurXComputer.getRun(file.computerRunId);
            if (run != null) return run.done && !run.ok;
        }
        return false;
    }
    private View commandCard(Attach file) {
        Context context = getContext();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(MusePage.rounded(MusePage.surface(context), 16, context));
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClickable(true);
        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.lemurx_muse_ic_code);
        icon.setColorFilter(MusePage.accent(context), PorterDuff.Mode.SRC_IN);
        head.addView(icon, new LinearLayout.LayoutParams(dp(16), dp(16)));
        LinearLayout titleCol = new LinearLayout(context);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(context);
        label.setText(humanizeCommand(context, commandText(file)));
        label.setTextColor(MusePage.text(context));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(label);
        boolean running = commandRunning(file);
        boolean failed = commandFailed(file);
        String statusText;
        int statusColor;
        statusText = running ? context.getString(R.string.lemurx_muse_command_running) : (failed ? context.getString(R.string.lemurx_muse_command_failed_tag) : context.getString(R.string.lemurx_muse_filter_done));
        statusColor = running ? MusePage.accent(context) : (failed ? 0xFFE53935 : MusePage.muted(context));
        TextView statusView = new TextView(context);
        statusView.setText(statusText);
        statusView.setTextColor(statusColor);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        titleCol.addView(statusView);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginStart(dp(8));
        head.addView(titleCol, titleLp);
        boolean expanded = file.computerRunId != null && mExpandedRuns.contains(file.computerRunId);
        TextView toggleDetails = new TextView(context);
        toggleDetails.setText(expanded ? R.string.lemurx_muse_command_collapse : R.string.lemurx_muse_command_details);
        toggleDetails.setTextColor(MusePage.accent(context));
        toggleDetails.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        toggleDetails.setPadding(dp(8), dp(4), dp(4), dp(4));
        head.addView(toggleDetails);
        card.addView(head);
        LinearLayout details = new LinearLayout(context);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setVisibility(expanded ? View.VISIBLE : View.GONE);
        LinearLayout subBar = new LinearLayout(context);
        subBar.setOrientation(LinearLayout.HORIZONTAL);
        subBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView subLabel = new TextView(context);
        subLabel.setText(R.string.lemurx_muse_command_label);
        subLabel.setTextColor(MusePage.muted(context));
        subLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subBar.addView(subLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        subBar.addView(commandChip(context, R.string.lemurx_muse_command_copy, true, v -> copyPlain(commandText(file), (TextView) v)));
        TextView open = commandChip(context, R.string.lemurx_muse_command_preview, false, v -> previewCommand(file));
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        openLp.setMarginStart(dp(4));
        subBar.addView(open, openLp);
        LinearLayout.LayoutParams subBarLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subBarLp.topMargin = dp(8);
        details.addView(subBar, subBarLp);
        TextView command = commandBody(context, commandText(file), MusePage.text(context));
        command.setBackground(MusePage.rounded(MusePage.bg(context), 10, context));
        LinearLayout.LayoutParams commandLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        commandLp.topMargin = dp(6);
        details.addView(command, commandLp);
        String output = commandOutput(file);
        if (!output.isEmpty()) {
            TextView out = commandBody(context, output, MusePage.muted(context));
            out.setMaxLines(8);
            out.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams outLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            outLp.topMargin = dp(6);
            details.addView(out, outLp);
        } else if (running) {
            TextView wait = new TextView(context);
            wait.setText(R.string.lemurx_muse_command_running);
            wait.setTextColor(MusePage.muted(context));
            wait.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            LinearLayout.LayoutParams waitLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            waitLp.topMargin = dp(6);
            details.addView(wait, waitLp);
        }
        View.OnClickListener toggleClick = v -> {
            LemurXHaptics.tick(v);
            boolean show = details.getVisibility() != View.VISIBLE;
            if (file.computerRunId != null) {
                if (show) mExpandedRuns.add(file.computerRunId);
                else mExpandedRuns.remove(file.computerRunId);
            }
            details.setVisibility(show ? View.VISIBLE : View.GONE);
            toggleDetails.setText(show ? R.string.lemurx_muse_command_collapse : R.string.lemurx_muse_command_details);
            scheduleWindow();
        };
        head.setOnClickListener(toggleClick);
        toggleDetails.setOnClickListener(toggleClick);
        card.addView(details);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.setMarginEnd(dp(16));
        card.setLayoutParams(lp);
        return card;
    }

    private TextView commandChip(
            Context context, int label, boolean filled, View.OnClickListener click) {
        TextView chip = new TextView(context);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        if (filled) {
            chip.setTextColor(MusePage.onAccent(context));
            chip.setBackground(MusePage.rounded(MusePage.accent(context), 12, context));
        } else {
            chip.setTextColor(MusePage.text(context));
        }
        chip.setOnClickListener(click);
        return chip;
    }

    private TextView commandBody(Context context, String text, int color) {
        TextView body = new TextView(context);
        body.setText(text);
        body.setTextColor(color);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(dp(10), dp(8), dp(10), dp(8));
        body.setLineSpacing(dp(2), 1f);
        return body;
    }

    private String commandText(Attach file) {
        if (file.computerRunId != null) {
            LemurXComputer.Run run = LemurXComputer.getRun(file.computerRunId);
            if (run != null && !run.command.isEmpty()) return run.command;
        }
        return file.name == null ? "" : file.name;
    }

    private String commandOutput(Attach file) {
        if (file.computerRunId != null) {
            LemurXComputer.Run run = LemurXComputer.getRun(file.computerRunId);
            if (run != null) {
                String body = run.output.toString().trim();
                if (!body.isEmpty()) return body;
                if (run.done && run.error != null && !run.error.isEmpty()) {
                    return computerFailure(run.error);
                }
                return "";
            }
            String local = LemurXVault.getRunOutput(file.computerRunId);
            if (local != null && !local.trim().isEmpty()) return local.trim();
        }
        String excerpt = file.excerpt == null ? "" : file.excerpt.trim();
        if ("…".equals(excerpt)) return "";
        return computerFailure(excerpt);
    }

    /** 服务端这句英文失败说明换成界面语言，不原样印出来。 */
    private String computerFailure(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if ("The computer did not run the command".equals(trimmed)) {
            return getContext().getString(R.string.lemurx_muse_command_failed);
        }
        return trimmed;
    }

    private boolean commandRunning(Attach file) {
        if (file.computerRunId != null) {
            LemurXComputer.Run run = LemurXComputer.getRun(file.computerRunId);
            if (run != null) return !run.done;
        }
        return "…".equals(file.excerpt);
    }

    private void copyPlain(String text, TextView label) {
        if (text == null || text.isEmpty()) return;
        ClipboardManager clipboard =
                (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("Aero", text));
        LemurXHaptics.tick(label);
        label.setText(R.string.lemurx_muse_command_copied);
        label.postDelayed(() -> label.setText(R.string.lemurx_muse_command_copy), 1200);
    }

    private String fileKindLabel(String kind) {
        if ("pdf".equals(kind)) return getContext().getString(R.string.lemurx_muse_file_pdf);
        if ("doc".equals(kind)) return getContext().getString(R.string.lemurx_muse_file_word);
        if ("image".equals(kind)) return getContext().getString(R.string.lemurx_muse_file_image);
        if ("computer".equals(kind)) return getContext().getString(R.string.lemurx_muse_file_computer);
        return getContext().getString(R.string.lemurx_muse_file_note);
    }

    private static int fileIcon(String kind) {
        if ("image".equals(kind)) return R.drawable.lemurx_muse_ic_file_image;
        if ("pdf".equals(kind)) return R.drawable.lemurx_muse_ic_file_pdf;
        if ("doc".equals(kind)) return R.drawable.lemurx_muse_ic_file_doc;
        return R.drawable.lemurx_muse_ic_file_text;
    }

    /** 模型用 write_file 交回来的文本。挂到当前回复上，点开预览，不排进 Tasks。 */
    private void takeWrittenFile(String content) {
        if (content == null || !content.contains("aeroFile")) return;
        try {
            JSONObject root = new JSONObject(content.trim());
            JSONObject file = root.optJSONObject("aeroFile");
            if (file == null) return;
            String name = officeName(file.optString("name", "notes.md"));
            String kind = officeKind(name, file.optString("kind", "text"));
            String text = file.optString("text", "");
            if (text.isEmpty()) return;
            Message host = hostForToolCard();
            if (host == null) return;
            boolean office = "pdf".equals(kind) || "doc".equals(kind);
            Attach written =
                    new Attach(name, text, office ? new ArrayList<>() : List.of(), kind, office);
            host.files.add(written);
            LemurXFiles.remember(getContext(), written.name, written.kind, written.excerpt);
            warmOffice(host);
            bindFiles(host);
            host.gen++;
            if (host.view != null && !TextUtils.isEmpty(host.text)) {
                renderInto(host.view, host.text);
            }
            if (mStickToBottom) scrollToBottom(true);
            scheduleWindow();
            persist();
        } catch (JSONException e) {
            Log.i(TAG, "write_file: %s", e.getMessage());
        }
    }

    /** 服务端 use_own_computer 的最终结果；和 CUSTOM 进度共用同一张卡片与电脑页 run。 */
    private void takeComputerRun(String content) {
        if (content == null || !content.contains("computerRunId")) return;
        try {
            JSONObject root = new JSONObject(content.trim());
            String runId = root.optString("computerRunId", "");
            if (runId.isEmpty()) return;
            String command = root.optString("command", "");
            boolean ok = root.optBoolean("ok", false);
            String output = root.optString("output", "");
            String error = root.optString("error", "");
            LemurXComputer.mirrorChatResult(
                    runId, command, output, ok, ok ? null : error, mThreadId);
            upsertComputerCard(
                    runId, command, ok ? output : (error.isEmpty() ? output : error), true);
        } catch (JSONException e) {
            Log.i(TAG, "use_own_computer: %s", e.getMessage());
        }
    }

    /** SSE CUSTOM own_computer：边跑边更新对话卡片，并镜像到电脑页。 */
    private void applyOwnComputer(JSONObject value) {
        String runId = value.optString("computerRunId", "");
        if (runId.isEmpty()) return;
        String command = value.optString("command", "");
        String phase = value.optString("phase", "");
        String host = value.optString("host", "");
        if ("start".equals(phase)) {
            LemurXComputer.beginRun(runId, command, "chat", mThreadId, host);
            upsertComputerCard(runId, command, "…", true);
            showStatus(humanizeCommand(getContext(), command) + "…");
            return;
        }
        if ("chunk".equals(phase)) {
            String line = value.optString("line", "");
            LemurXComputer.appendRun(runId, line);
            LemurXComputer.Run run = LemurXComputer.getRun(runId);
            // 行级进度只刷新卡片，不每次写存档；start/end 才落盘。
            upsertComputerCard(runId, command, run != null ? run.preview() : line, false);
            return;
        }
        if ("end".equals(phase)) {
            boolean ok = value.optBoolean("ok", false);
            String output = value.optString("output", "");
            String error = value.optString("error", "");
            if (LemurXComputer.getRun(runId) == null) {
                // 未加入 Tailscale 时服务端只发 end，没有 start。
                LemurXComputer.mirrorChatResult(
                        runId, command, output, ok, ok ? null : error, mThreadId);
            } else {
                LemurXComputer.endRun(runId, ok, ok ? null : error, output.isEmpty() ? null : output);
            }
            LemurXComputer.Run run = LemurXComputer.getRun(runId);
            String preview = run != null ? run.preview() : (ok ? output : error);
            upsertComputerCard(runId, command, preview, true);
        }
    }

    /** 电脑页自己跑完的命令：在本对话挂一张可点的卡片。 */
    void offerComputerRun(String runId, String command) {
        ThreadUtils.assertOnUiThread();
        if (mThreadId == null || TextUtils.isEmpty(runId)) return;
        LemurXComputer.Run run = LemurXComputer.getRun(runId);
        if (run != null) run.threadId = mThreadId;
        String preview = run != null ? run.preview() : "";
        upsertComputerCard(runId, command, preview, true);
    }

    private void upsertComputerCard(String runId, String command, String preview, boolean save) {
        Message host = hostForToolCard();
        if (host == null) return;
        String title = TextUtils.isEmpty(command)
                ? getContext().getString(R.string.lemurx_muse_computer_card)
                : command.replace('\n', ' ').replace(']', ' ').trim();
        if (title.length() > 400) title = title.substring(0, 400);
        for (Attach file : host.files) {
            if (runId.equals(file.computerRunId)) {
                file.excerpt = preview == null ? "" : preview;
                bindFiles(host);
                host.gen++;
                if (mStickToBottom) scrollToBottom(false);
                scheduleWindow();
                if (save) persist();
                return;
            }
        }
        if (host.view == null) append(host);
        host.files.add(new Attach(title, preview == null ? "" : preview, List.of(), "computer",
                false, runId));
        bindFiles(host);
        host.gen++;
        if (mStickToBottom) scrollToBottom(true);
        scheduleWindow();
        persist();
    }

    private @Nullable Message hostForToolCard() {
        Message host = mStreaming;
        if (host == null) {
            for (int i = mMessages.size() - 1; i >= 0; i--) {
                if ("assistant".equals(mMessages.get(i).role)) {
                    host = mMessages.get(i);
                    break;
                }
            }
        }
        if (host == null) {
            host = new Message(UUID.randomUUID().toString(), "assistant", "");
            append(host);
        }
        return host;
    }

    /** 点文件名。模型生成的 PDF / Word 先排好再打开，普通附件直接预览。 */
    private void openAttachment(Attach file) {
        if ("computer".equals(file.kind)) {
            previewCommand(file);
            return;
        }
        if (!file.generated || !file.images.isEmpty() || file.excerpt.isEmpty()) {
            preview(file);
            return;
        }
        submitAttachRead(
                () -> {
                    renderOffice(file);
                    new Handler(Looper.getMainLooper()).post(() -> preview(file));
                });
    }

    /** 文件名决定格式。.doc 也排成 docx，旧的二进制 Word 打不开。 */
    private static String officeName(String name) {
        String cleaned = name.replace('\\', ' ').replace('/', ' ').replace('\u0000', ' ').trim();
        if (cleaned.isEmpty()) cleaned = "notes.md";
        if (cleaned.length() > 80) cleaned = cleaned.substring(0, 80).trim();
        String lower = cleaned.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".doc")) cleaned = cleaned.substring(0, cleaned.length() - 4) + ".docx";
        return cleaned;
    }

    private static String officeKind(String name, String kind) {
        String lower = name.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".docx")) return "doc";
        return kind == null || kind.isEmpty() ? "text" : kind;
    }

    private void warmOffice(Message message) {
        for (Attach file : message.files) {
            if (!file.generated || file.rendering || !file.images.isEmpty() || file.excerpt.isEmpty()) {
                continue;
            }
            file.rendering = true;
            submitAttachRead(() -> renderOffice(file));
        }
    }

    /** 后台排版。页图和文件字节回到主线程再挂上，预览和打开用的是同一份结果。 */
    private void renderOffice(Attach file) {
        try {
            byte[] pdf = LemurXDocument.pdf(file.name, file.excerpt);
            byte[] stored = "doc".equals(file.kind) ? LemurXDocument.docx(file.name, file.excerpt) : pdf;
            List<String> pages = LemurXDocument.pageImages(pdf, 8);
            new Handler(Looper.getMainLooper())
                    .post(
                            () -> {
                                file.data = stored;
                                LemurXFiles.remember(getContext(), file.name, file.kind, file.excerpt);
                                if (file.images instanceof ArrayList) {
                                    file.images.clear();
                                    file.images.addAll(pages);
                                }
                            });
        } catch (IOException | RuntimeException e) {
            file.rendering = false;
            Log.i(TAG, "document: %s", e.getMessage());
        }
    }

    private void shareOffice(Attach file) {
        submitAttachRead(
                () -> {
                    try {
                        byte[] bytes = file.data;
                        if (bytes == null) {
                            bytes =
                                    "doc".equals(file.kind)
                                            ? LemurXDocument.docx(file.name, file.excerpt)
                                            : LemurXDocument.pdf(file.name, file.excerpt);
                            byte[] ready = bytes;
                            new Handler(Looper.getMainLooper()).post(() -> file.data = ready);
                        }
                        byte[] payload = bytes;
                        new Handler(Looper.getMainLooper()).post(() -> viewOffice(file, payload));
                    } catch (IOException | RuntimeException e) {
                        Log.i(TAG, "document open: %s", e.getMessage());
                        new Handler(Looper.getMainLooper())
                                .post(
                                        () ->
                                                showError(
                                                        getContext()
                                                                .getString(
                                                                        R.string.lemurx_muse_doc_no_app)));
                    }
                });
    }

    private void viewOffice(Attach file, byte[] bytes) {
        Context context = getContext();
        File dir = new File(context.getCacheDir(), "pdfs");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            showError(context.getString(R.string.lemurx_muse_doc_no_app));
            return;
        }
        File out = new File(dir, file.name);
        try (FileOutputStream stream = new FileOutputStream(out)) {
            stream.write(bytes);
        } catch (IOException e) {
            showError(context.getString(R.string.lemurx_muse_doc_no_app));
            return;
        }
        Uri uri = FileProviderUtils.getContentUriFromFile(out);
        if (uri == null) {
            uri = FileProvider.getUriForFile(context, context.getPackageName() + ".FileProvider", out);
        }
        String mime =
                "doc".equals(file.kind)
                        ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        : "application/pdf";
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClipData(ClipData.newRawUri(file.name, uri));
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            showError(context.getString(R.string.lemurx_muse_doc_no_app));
        }
    }

    /** 附件预览正开着。返回键先收它。 */
    public static boolean previewOpen() {
        MuseChatView owner = sPreviewOwner;
        return owner != null && owner.mPreview != null;
    }

    /** 收起附件预览。没开着时返回 false。 */
    public static boolean closePreview() {
        MuseChatView owner = sPreviewOwner;
        if (owner == null) return false;
        return owner.dismissPreview();
    }

    private void preview(Attach file) {
        dismissPreview();
        Context context = getContext();
        FrameLayout stage = new FrameLayout(context);
        stage.setBackgroundColor(MusePage.bg(context));
        stage.setClickable(true);
        stage.setElevation(dp(24));
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(12), dp(16), dp(16));
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText(file.name);
        title.setTextColor(MusePage.text(context));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (file.generated) {
            TextView open = new TextView(context);
            open.setText(R.string.lemurx_muse_doc_open);
            open.setTextColor(MusePage.text(context));
            open.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            open.setPadding(dp(14), dp(8), dp(14), dp(8));
            open.setOnClickListener(v -> shareOffice(file));
            LinearLayout.LayoutParams openLp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            openLp.setMarginEnd(dp(8));
            bar.addView(open, openLp);
        }
        TextView close = new TextView(context);
        close.setText(R.string.lemurx_muse_preview_close);
        close.setTextColor(MusePage.onAccent(context));
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        close.setBackground(MusePage.rounded(MusePage.accent(context), 14, context));
        close.setPadding(dp(14), dp(8), dp(14), dp(8));
        close.setOnClickListener(v -> dismissPreview());
        bar.addView(close);
        column.addView(bar);
        ScrollView scroll = new ScrollView(context);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(16), 0, dp(24));
        boolean shown = false;
        for (String jpeg : file.images) {
            byte[] bytes;
            try {
                bytes = Base64.decode(jpeg, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                continue;
            }
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) continue;
            ImageView image = new ImageView(context);
            image.setImageBitmap(bitmap);
            image.setAdjustViewBounds(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(12);
            body.addView(image, lp);
            shown = true;
        }
        if (!shown && !file.excerpt.isEmpty()) {
            TextView page = new TextView(context);
            page.setText(file.excerpt);
            page.setTextColor(MusePage.text(context));
            page.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            page.setTextIsSelectable(true);
            body.addView(page);
            shown = true;
        }
        if (!shown) {
            TextView missing = new TextView(context);
            missing.setText(R.string.lemurx_muse_preview_missing);
            missing.setTextColor(MusePage.muted(context));
            missing.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            body.addView(missing);
        }
        scroll.addView(body);
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        stage.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.bringToFront();
        mPreview = stage;
        sPreviewOwner = this;
        BackPressManager.lemurXConsoleStateChanged();
    }

    /** 命令全文：上面是指令，下面的输出可以自己滚动、选中、复制。 */
    private void previewCommand(Attach file) {
        dismissPreview();
        Context context = getContext();
        String command = commandText(file);
        String output = commandOutput(file);
        FrameLayout stage = new FrameLayout(context);
        stage.setBackgroundColor(MusePage.bg(context));
        stage.setClickable(true);
        stage.setElevation(dp(24));
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(12), dp(16), dp(16));

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText(R.string.lemurx_muse_command_label);
        title.setTextColor(MusePage.text(context));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView copyCommand = commandChip(
                context,
                R.string.lemurx_muse_command_copy,
                true,
                v -> copyPlain(command, (TextView) v));
        LinearLayout.LayoutParams copyLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyLp.setMarginEnd(dp(8));
        bar.addView(copyCommand, copyLp);
        TextView close = new TextView(context);
        close.setText(R.string.lemurx_muse_preview_close);
        close.setTextColor(MusePage.onAccent(context));
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        close.setBackground(MusePage.rounded(MusePage.accent(context), 14, context));
        close.setPadding(dp(14), dp(8), dp(14), dp(8));
        close.setOnClickListener(v -> dismissPreview());
        bar.addView(close);
        column.addView(bar);

        ScrollView commandScroll = new ScrollView(context);
        commandScroll.setFillViewport(true);
        TextView commandView = commandBody(context, command, MusePage.text(context));
        commandView.setBackground(MusePage.rounded(MusePage.surface(context), 12, context));
        commandScroll.addView(commandView);
        LinearLayout.LayoutParams commandLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        commandLp.topMargin = dp(12);
        column.addView(commandScroll, commandLp);
        int commandCap = dp(180);
        commandScroll.addOnLayoutChangeListener(
                new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(
                            View v,
                            int left,
                            int top,
                            int right,
                            int bottom,
                            int oldLeft,
                            int oldTop,
                            int oldRight,
                            int oldBottom) {
                        if (v.getHeight() <= commandCap) return;
                        v.removeOnLayoutChangeListener(this);
                        ViewGroup.LayoutParams params = v.getLayoutParams();
                        params.height = commandCap;
                        v.setLayoutParams(params);
                    }
                });

        LinearLayout outBar = new LinearLayout(context);
        outBar.setOrientation(LinearLayout.HORIZONTAL);
        outBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView outLabel = new TextView(context);
        outLabel.setText(R.string.lemurx_muse_command_output);
        outLabel.setTextColor(MusePage.muted(context));
        outLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        outBar.addView(
                outLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!output.isEmpty()) {
            outBar.addView(
                    commandChip(
                            context,
                            R.string.lemurx_muse_command_copy,
                            false,
                            v -> copyPlain(output, (TextView) v)));
        }
        LinearLayout.LayoutParams outBarLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        outBarLp.topMargin = dp(16);
        outBarLp.bottomMargin = dp(8);
        column.addView(outBar, outBarLp);

        ScrollView outputScroll = new ScrollView(context);
        outputScroll.setFillViewport(true);
        TextView outputView = commandBody(
                context,
                output.isEmpty()
                        ? context.getString(
                                commandRunning(file)
                                        ? R.string.lemurx_muse_command_running
                                        : R.string.lemurx_muse_command_empty)
                        : output,
                output.isEmpty() ? MusePage.muted(context) : MusePage.text(context));
        outputView.setBackground(MusePage.rounded(MusePage.surface(context), 12, context));
        outputScroll.addView(
                outputView,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(
                outputScroll,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        stage.addView(
                column,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(
                stage,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.bringToFront();
        mPreview = stage;
        sPreviewOwner = this;
        BackPressManager.lemurXConsoleStateChanged();
    }

    private boolean dismissPreview() {
        View stage = mPreview;
        if (stage == null) return false;
        removeView(stage);
        mPreview = null;
        if (sPreviewOwner == this) sPreviewOwner = null;
        BackPressManager.lemurXConsoleStateChanged();
        return true;
    }

    /** 本机先摆上上次留下的对话。没有存档就返回 false，界面先空着等网络。 */
    private boolean paintCached(String threadId) {
        mCacheCount = 0;
        String raw = readCache(threadId);
        if (raw == null) return false;
        try {
            JSONArray messages = new JSONArray(raw);
            mBatch = true;
            int n = appendStored(messages, threadId);
            mBatch = false;
            mCacheCount = mMessages.size();
            if (n > 0) scheduleWindow();
            return n > 0;
        } catch (JSONException e) {
            mCacheCount = 0;
            return false;
        }
    }

    private int appendStored(JSONArray messages, String threadId) {
        int n = 0;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "");
            String content = m.optString("content", "");
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            if (TextUtils.isEmpty(content) && !"user".equals(role)) continue;
            Message loaded = decodeStored(
                    m.optString("id", UUID.randomUUID().toString()), role, content);
            if (TextUtils.isEmpty(loaded.text) && loaded.files.isEmpty()) continue;
            for (Attach file : loaded.files) {
                if ("computer".equals(file.kind) && file.computerRunId != null) {
                    LemurXComputer.Run run = LemurXComputer.getRun(file.computerRunId);
                    if (run != null) run.threadId = threadId;
                }
            }
            append(loaded);
            n++;
        }
        restoreImages(threadId);
        return n;
    }

    /** 屏幕上这份和刚拉下来的是不是同一段。是的话就别清掉重画。 */
    private boolean sameAsShowing(JSONArray incoming) {
        int n = 0;
        for (int i = 0; i < incoming.length(); i++) {
            JSONObject m = incoming.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "");
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            String content = m.optString("content", "");
            if (TextUtils.isEmpty(content) && !"user".equals(role)) continue;
            if (n >= mMessages.size()) return false;
            Message have = mMessages.get(n);
            if (!role.equals(have.role)) return false;
            String id = m.optString("id", "");
            if (!id.isEmpty() && !id.equals(have.id)) return false;
            Message loaded = decodeStored(have.id, role, content);
            if (!TextUtils.equals(loaded.text, have.text)) return false;
            if (loaded.files.size() != have.files.size()) return false;
            n++;
        }
        return n == mMessages.size();
    }

    /**
     * 图片字节只留在本机，重新打开后还能再发给模型。对话正文上传时不带这些字节。
     * 只留最新两张，和一次请求的预算一致。
     */
    private void writeImages() {
        if (mThreadId == null) return;
        JSONArray kept = new JSONArray();
        int n = 0;
        for (int i = mMessages.size() - 1; i >= 0 && n < 2; i--) {
            Message message = mMessages.get(i);
            if (!"user".equals(message.role)) continue;
            for (int f = message.files.size() - 1; f >= 0 && n < 2; f--) {
                Attach file = message.files.get(f);
                if (file.generated) continue;
                for (int p = file.images.size() - 1; p >= 0 && n < 2; p--) {
                    try {
                        JSONObject one = new JSONObject();
                        one.put("id", message.id);
                        one.put("name", file.name);
                        one.put("jpeg", file.images.get(p));
                        kept.put(one);
                        n++;
                    } catch (JSONException e) {
                        // 字段都是字符串
                    }
                }
            }
        }
        try {
            File dir = new File(ContextUtils.getApplicationContext().getFilesDir(), "lemurx-chat");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File file = new File(dir, mThreadId + ".pix");
            if (kept.length() == 0) {
                if (file.exists() && !file.delete()) Log.i(TAG, "pix: delete");
                return;
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(kept.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.i(TAG, "pix: %s", e.getMessage());
        }
    }

    /** 把本机留下的图片填回对应附件。已经有字节的不动。 */
    private void restoreImages(String threadId) {
        File file = new File(
                new File(ContextUtils.getApplicationContext().getFilesDir(), "lemurx-chat"),
                threadId + ".pix");
        if (!file.isFile() || file.length() == 0 || file.length() > 2_000_000) return;
        String raw;
        try {
            byte[] buf = new byte[(int) file.length()];
            try (InputStream in = new java.io.FileInputStream(file)) {
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n < 0) break;
                    off += n;
                }
                if (off != buf.length) return;
            }
            raw = new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return;
        }
        JSONArray kept;
        try {
            kept = new JSONArray(raw);
        } catch (JSONException e) {
            return;
        }
        for (int i = 0; i < kept.length(); i++) {
            JSONObject one = kept.optJSONObject(i);
            if (one == null) continue;
            String id = one.optString("id", "");
            String name = one.optString("name", "");
            String jpeg = one.optString("jpeg", "");
            if (id.isEmpty() || jpeg.isEmpty()) continue;
            for (Message message : mMessages) {
                if (!id.equals(message.id)) continue;
                for (Attach attach : message.files) {
                    if (!name.equals(attach.name) || attach.generated) continue;
                    if (!(attach.images instanceof ArrayList)) continue;
                    if (!attach.images.contains(jpeg)) attach.images.add(jpeg);
                }
            }
        }
    }

    private void writeCache(JSONArray messages) {
        if (mThreadId == null || messages == null) return;
        try {
            File dir = new File(ContextUtils.getApplicationContext().getFilesDir(), "lemurx-chat");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File file = new File(dir, mThreadId + ".json");
            File tmp = new File(dir, mThreadId + ".json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(messages.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file) && file.exists() && !file.delete()) return;
            if (!file.exists()) tmp.renameTo(file);
        } catch (Exception e) {
            Log.i(TAG, "cache: %s", e.getMessage());
        }
    }

    private static @Nullable String readCache(String threadId) {
        try {
            File file = new File(
                    new File(ContextUtils.getApplicationContext().getFilesDir(), "lemurx-chat"),
                    threadId + ".json");
            if (!file.isFile() || file.length() == 0 || file.length() > 2_000_000) return null;
            byte[] buf = new byte[(int) file.length()];
            try (InputStream in = new java.io.FileInputStream(file)) {
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n < 0) break;
                    off += n;
                }
                if (off != buf.length) return null;
            }
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private void persist() {
        if (mThreadId == null || !mLoaded) return;
        JSONArray messages = archiveJson();
        // 服务端对空的非 default 会话会删行。没消息就不要写，免得加载竞态把已有历史抹掉。
        if (messages.length() == 0) return;
        writeCache(messages);
        writeImages();
        JSONObject body = new JSONObject();
        try {
            body.put("id", mThreadId);
            body.put("messages", messages);
        } catch (JSONException e) {
            return;
        }
        MuseApi.get()
                .send(
                        "PUT",
                        "/api/conversation",
                        body,
                        (value, error) -> {
                            if (error != null) Log.i(TAG, "persist: %s", error);
                        });
    }

    void destroy() {
        dismissPreview();
        mAttachGen++;
        mAttachRead.shutdownNow();
        stop();
        if (mThreadId != null) sLive.remove(mThreadId);
        ThreadUtils.assertOnUiThread();
    }

    /**
     * 点在文件名上就打开预览。气泡自己的选择手势不会触发 ClickableSpan。
     */
    private boolean openTappedFile(TextView view, MotionEvent event) {
        CharSequence raw = view.getText();
        if (!(raw instanceof Spannable)) return false;
        Layout layout = view.getLayout();
        if (layout == null || view.hasSelection()) return false;
        int x = (int) event.getX() - view.getTotalPaddingLeft() + view.getScrollX();
        int y = (int) event.getY() - view.getTotalPaddingTop() + view.getScrollY();
        if (y < 0 || y > view.getHeight()) return false;
        int line = layout.getLineForVertical(y);
        int off = layout.getOffsetForHorizontal(line, x);
        ClickableSpan[] links = ((Spannable) raw).getSpans(Math.max(0, off - 1), off, ClickableSpan.class);
        if (links.length == 0) return false;
        links[0].onClick(view);
        return true;
    }

    /**
     * 手指按住接近长按阈值时，才让这一条拿焦点。点一下、滑走都不聚焦，输入框不会因此把键盘收起来。
     *
     * @return false，触摸继续交给文字自己的选择逻辑。点到文件名时收掉这次触摸并打开预览。
     */
    private boolean trackSelectionArm(TextView view, MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (TextUtils.isEmpty(view.getText())) break;
                // 先不让列表把这次按住抢走，长按才能弹出复制。滑出阈值再还给滚动。
                mScroll.requestDisallowInterceptTouchEvent(true);
                mScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                mArmX = event.getX();
                mArmY = event.getY();
                mPressGen++;
                int gen = mPressGen;
                view.postDelayed(
                        () -> beginSelection(view, gen), ViewConfiguration.getLongPressTimeout());
                break;
            case MotionEvent.ACTION_MOVE:
                if (mDragSelecting) {
                    extendSelection(view, event);
                    scrollSelection(event);
                    return true;
                }
                if (mSelecting != null) break;
                float dx = event.getX() - mArmX;
                float dy = event.getY() - mArmY;
                int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                if (dx * dx + dy * dy > (float) slop * slop) {
                    mPressGen++;
                    mScroll.requestDisallowInterceptTouchEvent(false);
                    mScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mDragSelecting) {
                    mDragSelecting = false;
                    mScroll.requestDisallowInterceptTouchEvent(false);
                    return true;
                }
                // 点在文件名上就打开预览。选择逻辑不会把这次点击交给 ClickableSpan。
                if (action == MotionEvent.ACTION_UP && mSelecting != view && openTappedFile(view, event)) {
                    mPressGen++;
                    disarm(view);
                    return true;
                }
                // 监听器先于文字自己的触摸处理。点一下时先把焦点挡回去，避免松手把键盘收起。
                // 长按已经建好选区时不能挡，否则工具条马上消失。
                if (mSelecting != view) {
                    mPressGen++;
                    disarm(view);
                }
                break;
            default:
                break;
        }
        return false;
    }

    private void beginSelection(TextView view, int gen) {
        if (gen != mPressGen || mSelecting != null || !view.isAttachedToWindow()) return;
        CharSequence raw = view.getText();
        if (TextUtils.isEmpty(raw)) return;
        if (!(raw instanceof Spannable)) {
            view.setText(raw, TextView.BufferType.SPANNABLE);
            raw = view.getText();
        }
        if (!(raw instanceof Spannable) || raw.length() == 0) return;
        Spannable text = (Spannable) raw;
        int offset = view.getOffsetForPosition(mArmX, mArmY);
        int start = offset <= 0 ? 0 : Math.min(offset, text.length() - 1);
        int end = start;
        while (start > 0 && isWordChar(text.charAt(start - 1))) start--;
        while (end < text.length() && isWordChar(text.charAt(end))) end++;
        if (end <= start) end = Math.min(text.length(), start + 1);
        mSelAnchor = start;
        mDragSelecting = true;
        mScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        mScroll.requestDisallowInterceptTouchEvent(true);
        Selection.setSelection(text, start, end);
        mSelecting = view;
        view.requestFocus();
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (view.startActionMode(selectionMenu(view), ActionMode.TYPE_FLOATING) == null) {
            mSelecting = null;
            mDragSelecting = false;
            Selection.removeSelection(text);
            disarm(view);
        }
    }

    /** 手指还按着时，选区从长按的那一头拉到当前这一点。 */
    private void extendSelection(TextView view, MotionEvent event) {
        CharSequence raw = view.getText();
        if (!(raw instanceof Spannable) || raw.length() == 0) return;
        int off = view.getOffsetForPosition(event.getX(), event.getY());
        off = Math.max(0, Math.min(off, raw.length()));
        int a = Math.min(mSelAnchor, off);
        int b = Math.max(mSelAnchor, off);
        if (b <= a) b = Math.min(raw.length(), a + 1);
        Selection.setSelection((Spannable) raw, a, b);
    }

    /** 拖到列表边上时跟着滚，长段落才能一次选完。 */
    private void scrollSelection(MotionEvent event) {
        int[] loc = new int[2];
        mScroll.getLocationOnScreen(loc);
        float y = event.getRawY();
        int edge = dp(36);
        if (y < loc[1] + edge) mScroll.scrollBy(0, -dp(14));
        else if (y > loc[1] + mScroll.getHeight() - edge) mScroll.scrollBy(0, dp(14));
        mScroll.requestDisallowInterceptTouchEvent(true);
    }

    private static boolean isWordChar(char c) {
        if (Character.isWhitespace(c)) return false;
        return "，。！？、；：,.!?;:()（）【】[]\"'“”‘’".indexOf(c) < 0;
    }

    private void disarm(TextView view) {
        if (view.isFocused()) view.clearFocus();
        if (mSelecting == null) {
            mScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
    }

    /**
     * 不往菜单里加项，系统已经有复制、全选、分享。这里只记住选区还在，结束时把焦点让回去。
     */
    private ActionMode.Callback selectionMode(TextView view) {
        return new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                mSelecting = view;
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                if (mSelecting == view) mSelecting = null;
                mDragSelecting = false;
                disarm(view);
                scheduleWindow();
            }
        };
    }

    /** 系统没有弹出选择条时，用这一条给出复制、全选、分享。 */
    private ActionMode.Callback selectionMenu(TextView view) {
        return new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                mSelecting = view;
                menu.add(Menu.NONE, android.R.id.copy, 0, android.R.string.copy);
                menu.add(Menu.NONE, android.R.id.selectAll, 1, android.R.string.selectAll);
                int share = view.getContext().getResources().getIdentifier("share", "string", "android");
                if (share != 0) menu.add(Menu.NONE, android.R.id.shareText, 2, share);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                int id = item.getItemId();
                if (id == android.R.id.selectAll) {
                    CharSequence text = view.getText();
                    if (text instanceof Spannable) Selection.selectAll((Spannable) text);
                    return true;
                }
                String selected = selectedText(view);
                if (selected == null) return false;
                if (id == android.R.id.copy) {
                    ClipboardManager clipboard =
                            (ClipboardManager)
                                    view.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Aero", selected));
                    }
                    mode.finish();
                    return true;
                }
                if (id == android.R.id.shareText) {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, selected);
                    view.getContext().startActivity(Intent.createChooser(send, null));
                    mode.finish();
                    return true;
                }
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                CharSequence text = view.getText();
                if (text instanceof Spannable) Selection.removeSelection((Spannable) text);
                if (mSelecting == view) mSelecting = null;
                mDragSelecting = false;
                disarm(view);
                scheduleWindow();
            }
        };
    }

    private static @Nullable String selectedText(TextView view) {
        CharSequence text = view.getText();
        int start = view.getSelectionStart();
        int end = view.getSelectionEnd();
        if (text == null || start < 0 || end < 0 || start == end) return null;
        return text.subSequence(Math.min(start, end), Math.max(start, end)).toString();
    }

    /**
     * 助手消息里的链接要点击打开。{@link ArrowKeyMovementMethod} 负责拖选，点在链接上且没有选区时才打开。
     */
    private static final class LinkSelectMovementMethod extends ArrowKeyMovementMethod {
        private static final LinkSelectMovementMethod INSTANCE = new LinkSelectMovementMethod();

        public static LinkSelectMovementMethod getInstance() {
            return INSTANCE;
        }

        @Override
        public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                Layout layout = widget.getLayout();
                if (layout != null && !widget.hasSelection()) {
                    int x = (int) event.getX() - widget.getTotalPaddingLeft() + widget.getScrollX();
                    int y = (int) event.getY() - widget.getTotalPaddingTop() + widget.getScrollY();
                    int line = layout.getLineForVertical(y);
                    int off = layout.getOffsetForHorizontal(line, x);
                    ClickableSpan[] links = buffer.getSpans(off, off, ClickableSpan.class);
                    if (links.length != 0) {
                        links[0].onClick(widget);
                        return true;
                    }
                }
            }
            return super.onTouchEvent(widget, buffer, event);
        }
    }
}
