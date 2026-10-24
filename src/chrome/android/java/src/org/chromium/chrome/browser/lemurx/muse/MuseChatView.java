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
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
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

import androidx.core.content.FileProvider;

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
import org.chromium.chrome.browser.tab.Tab;
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
        /** 正文和文件卡片分开挂。窗口滚动时整行一起进出。 */
        @Nullable LinearLayout row;
        /** 文本或宽度变了就加一，用来判断离屏高度缓存还能不能用。 */
        int gen;
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
    private final TextView mStatus;
    private final TextView mError;
    private final TextView mPages;
    private final LinearLayout mSuspend;
    private boolean mSuspended;
    /** 列表末尾的"Agent 正在…"那一行：三个跳动的点 + 可选的说明文字（工具名）。 */
    private final LinearLayout mTyping;
    private final EditText mInput;
    private final ImageView mSend;
    private final ImageView mMic;
    private final ImageView mAttach;
    private final HorizontalScrollView mAttachScroll;
    private final LinearLayout mAttachRow;
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

    private final List<Message> mMessages = new ArrayList<>();
    private @Nullable String mThreadId;
    private boolean mLoading;
    private boolean mLoaded;
    private MuseApi.@Nullable Run mRun;
    /** 拦住游客操作后加一，让被取消的那一轮回调不再改界面。 */
    private int mRunGen;
    private @Nullable Message mStreaming;
    /** 打字机：已经画到这条回复的第几个字符。落后于 {@link Message#text} 时逐帧往前赶。 */
    private int mTyped;
    private boolean mTypePending;
    private boolean mTypeFinished;
    private @Nullable Message mTypeMessage;
    private boolean mStickToBottom = true;
    /** 刚进入对话，滚动监听先别把“贴底”清掉，等滚到最新一条再说。 */
    private boolean mHoldBottom;
    private boolean mImeSuppressed;
    /**
     * 正在选字的那一条。平时气泡不拿焦点，滚动不去子孙里做选区计算；长按发生前一小段才把这一条
     * 放开，系统工具条（复制、全选、分享）才能出来。选区还在时不能把气泡摘下树，否则选区消失。
     */
    private @Nullable TextView mSelecting;
    private int mPressGen;
    private float mArmX;
    private float mArmY;
    /** 空会话建议的代数：晚到的画像响应不能盖掉用户已经开始打的那一轮。 */
    private int mSuggestGen;

    MuseChatView(Context context, MusePage page) {
        super(context);
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
        mScroll.getViewTreeObserver()
                .addOnScrollChangedListener(
                        () -> {
                            if (mHoldBottom) return;
                            View child = mScroll.getChildAt(0);
                            if (child == null) return;
                            int bottom = child.getHeight() - mScroll.getHeight() - mScroll.getScrollY();
                            mStickToBottom = bottom < dp(80);
                            updateJump();
                            scheduleWindow();
                        });
        FrameLayout listWrap = new FrameLayout(context);
        mList = new LinearLayout(context);
        mList.setOrientation(LinearLayout.VERTICAL);
        mList.setPadding(pad, dp(8), pad, dp(12));
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
        column.addView(mAttachScroll, attachLp);

        // ---- 输入栏
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.BOTTOM);
        bar.setBackground(MusePage.rounded(MusePage.surface(getContext()), 26, context));
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        mAttach = new ImageView(context);
        mAttach.setImageResource(R.drawable.lemurx_muse_ic_plus);
        mAttach.setColorFilter(MusePage.text(context), PorterDuff.Mode.SRC_IN);
        mAttach.setContentDescription(context.getString(R.string.lemurx_muse_attach));
        mAttach.setClickable(true);
        // 和发送钮一样不能抢焦点，否则按下时键盘先收、底栏回来，点按落到药丸上。
        mAttach.setFocusable(false);
        mAttach.setFocusableInTouchMode(false);
        mAttach.setPadding(dp(9), dp(9), dp(9), dp(9));
        mAttach.setOnClickListener(v -> onAttachClicked());
        bar.addView(mAttach, new LinearLayout.LayoutParams(dp(40), dp(40)));
        mInput = new EditText(context);
        mInput.setHint(R.string.lemurx_muse_input_hint);
        mInput.setHintTextColor(MusePage.muted(getContext()));
        mInput.setTextColor(MusePage.text(getContext()));
        mInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mInput.setBackground(null);
        mInput.setMaxLines(6);
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
        mInput.setPadding(0, dp(10), dp(8), dp(10));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mInput.setTextCursorDrawable(new ColorDrawable(MusePage.text(context)));
        }
        bar.addView(mInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mMic = new ImageView(context);
        mMic.setImageResource(R.drawable.lemurx_muse_ic_mic);
        mMic.setContentDescription(context.getString(R.string.lemurx_muse_voice));
        mMic.setClickable(true);
        // 和发送钮一样不能抢焦点，否则按下时键盘先收、底栏回来，点按落到药丸上。
        mMic.setFocusable(false);
        mMic.setFocusableInTouchMode(false);
        mMic.setPadding(dp(9), dp(9), dp(9), dp(9));
        mMic.setOnClickListener(v -> onMicClicked());
        paintMic();
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        micLp.setMargins(0, 0, dp(4), 0);
        bar.addView(mMic, micLp);
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
        mSend.setPadding(dp(9), dp(9), dp(9), dp(9));
        mSend.setOnClickListener(v -> onSendClicked());
        bar.addView(mSend, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.setMargins(pad, 0, pad, dp(10));
        column.addView(bar, barLp);

        addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 软键盘弹出时把底部药丸收起来，输入栏贴着键盘；收起后再放回去。
        setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    updateImeSuppression();
                    return insets;
                });
        mInput.setOnFocusChangeListener((v, hasFocus) -> updateImeSuppression());
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
    protected void onDetachedFromWindow() {
        releaseVoice();
        if (mImeSuppressed) {
            mImeSuppressed = false;
            LemurXConsoleShell.setNavSuppressed(this, false);
        }
        super.onDetachedFromWindow();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility != View.VISIBLE && mMic != null) {
            endVoice(true);
            if (mImeSuppressed) {
                mImeSuppressed = false;
                LemurXConsoleShell.setNavSuppressed(this, false);
            }
        }
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
        mLoaded = false;
        showError(null);
        updateEmpty();
        final String id = threadId;
        mLoading = true;
        MuseApi.get()
                .get(
                        "/api/conversation?id=" + id,
                        (value, error) -> {
                            if (!id.equals(mThreadId)) return;
                            mLoading = false;
                            if (error != null) {
                                showError(error);
                                return;
                            }
                            mLoaded = true;
                            JSONArray messages =
                                    value instanceof JSONObject
                                            ? ((JSONObject) value).optJSONArray("messages")
                                            : null;
                            if (messages == null) messages = new JSONArray();
                            // 可能在拉历史期间用户已经发了消息：历史放前面。
                            List<Message> typed = new ArrayList<>(mMessages);
                            mMessages.clear();
                            mList.removeAllViews();
                            mWinStart = -1;
                            mWinEnd = -1;
                            mTopSpan = -1;
                            mBotSpan = -1;
                            mBatch = true;
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
                                append(loaded);
                            }
                            for (Message m : typed) append(m);
                            mBatch = false;
                            scheduleWindow();
                            rememberTitle();
                            notifyTitleChanged();
                            revealLatest();
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
        boolean empty = mMessages.isEmpty() && !mLoading;
        if (empty) buildSuggestions();
        mEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        updateJump();
    }

    /** 列表比视口高且没贴底时显示"回到最新"。 */
    private void updateJump() {
        View child = mScroll.getChildAt(0);
        boolean show = child != null
                && child.getHeight() > mScroll.getHeight() + dp(8)
                && !mStickToBottom
                && !mMessages.isEmpty();
        mJump.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /**
     * 开场建议：先画兜底（有网页就带"总结当前网页"），再换成该用户搜索习惯图上的条目。
     * 点一下就发出去。
     */
    private void buildSuggestions() {
        Context context = getContext();
        String web = LemurXConsoleShell.tabWebUrl(mTab);
        paintSuggestions(fallbackSuggestions(context, web));
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
                            List<String[]> items = startersFrom(value);
                            if (items.isEmpty()) return;
                            paintSuggestions(items);
                        });
    }

    private List<String[]> fallbackSuggestions(Context context, @Nullable String web) {
        List<String[]> items = new ArrayList<>();
        if (web != null) {
            items.add(new String[] {
                context.getString(R.string.lemurx_muse_suggest_summarize),
                context.getString(R.string.lemurx_muse_suggest_summarize_prompt, web)
            });
        }
        items.add(new String[] {
            context.getString(R.string.lemurx_muse_suggest_news),
            context.getString(R.string.lemurx_muse_suggest_news_prompt)
        });
        items.add(new String[] {
            context.getString(R.string.lemurx_muse_suggest_watch),
            context.getString(R.string.lemurx_muse_suggest_watch_prompt)
        });
        items.add(new String[] {
            context.getString(R.string.lemurx_muse_suggest_plan),
            context.getString(R.string.lemurx_muse_suggest_plan_prompt)
        });
        return items;
    }

    private static List<String[]> startersFrom(@Nullable Object value) {
        List<String[]> items = new ArrayList<>();
        if (!(value instanceof JSONObject)) return items;
        JSONArray rows = ((JSONObject) value).optJSONArray("items");
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

    private void paintSuggestions(List<String[]> items) {
        Context context = getContext();
        mSuggestions.removeAllViews();
        for (String[] item : items) {
            TextView chip = new TextView(context);
            chip.setText(item[0]);
            chip.setTextColor(MusePage.text(getContext()));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(MusePage.rounded(MusePage.surface(getContext()), 14, context));
            chip.setPadding(dp(16), dp(12), dp(16), dp(12));
            chip.setClickable(true);
            chip.setFocusable(true);
            final String prompt = item[1];
            chip.setOnClickListener(
                    v -> {
                        LemurXHaptics.tick(v);
                        mInput.setText(prompt);
                        send();
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            mSuggestions.addView(chip, lp);
        }
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
        if ("user".equals(message.role)) {
            view.setTextColor(MusePage.text(getContext()));
            view.setBackground(MusePage.rounded(MusePage.surface(getContext()), 20, context));
            view.setPadding(dp(14), dp(10), dp(14), dp(10));
            paintUser(view, message);
            lp.gravity = Gravity.END;
            lp.setMargins(dp(48), dp(10), 0, dp(10));
        } else {
            view.setTextColor(MusePage.text(getContext()));
            view.setPadding(dp(2), dp(6), dp(2), dp(6));
            view.setMovementMethod(LinkSelectMovementMethod.getInstance());
            view.setLinkTextColor(MusePage.link(context));
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.setMargins(0, dp(6), 0, dp(6));
            renderInto(view, message.text);
        }
        message.view = view;
        view.setLayoutParams(lp);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.addView(view);
        message.row = row;
        bindFiles(message);
        if (!"user".equals(message.role)) row.addView(shareChip(message));
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
        int viewport = Math.max(mScroll.getHeight(), 1);
        int maxScroll = Math.max(0, content - viewport);
        // 只有刚进入对话才按贴底算窗口。用户已经滑开时跟手指走，不能再拽回底部。
        int scroll = mHoldBottom ? maxScroll : mScroll.getScrollY();
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
            if (mHoldBottom && mScroll.getScrollY() < scroll - dp(4)) {
                mScroll.post(this::finishPinBottom);
            }
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
        CharSequence body =
                MuseMarkdown.render(markdown, MusePage.text(getContext()), this::openInNewTab);
        view.setText(body);
        view.setVisibility(TextUtils.isEmpty(body) ? View.GONE : View.VISIBLE);
        if (message != null) bindFiles(message);
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
        mTypeFinished = false;
        scheduleType();
    }

    /**
     * 打字机：目标文本已经在 {@link Message#text} 里攒着，这里按积压多少一次吐
     * 几个字。还没追上时用纯文本（半截 markdown 会花），追上了再渲一遍。
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
            int backlog = have - mTyped;
            int step = backlog > 120 ? 16 : backlog > 40 ? 6 : backlog > 12 ? 2 : 1;
            mTyped = Math.min(have, mTyped + step);
            String shown = m.text.substring(0, mTyped);
            m.gen++;
            if (mTyped < have) {
                m.view.setText(shown);
            } else {
                renderInto(m.view, shown);
            }
            if (mStickToBottom) scrollToBottom(false);
            updateJump();
            scheduleWindow();
            scheduleType();
            return;
        }
        if (mTypeFinished && !TextUtils.isEmpty(m.text)) {
            m.gen++;
            renderInto(m.view, m.text);
            if (mStickToBottom) scrollToBottom(true);
            updateJump();
            scheduleWindow();
        }
    }

    private void scrollToBottom(boolean smooth) {
        mScroll.post(
                () -> {
                    // 不用 fullScroll：它会把焦点挪到列表里的文字上，输入框失焦、键盘缩回去。
                    if (mHoldBottom && !smooth) {
                        finishPinBottom();
                        return;
                    }
                    View child = mScroll.getChildAt(0);
                    int bottom = child == null ? 0 : child.getHeight();
                    if (smooth) {
                        mScroll.smoothScrollTo(0, bottom);
                    } else {
                        mScroll.scrollTo(0, bottom);
                    }
                });
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
        mError.setText(error == null ? "" : error);
        mError.setVisibility(error == null ? View.GONE : View.VISIBLE);
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
        mInput.setText(getContext().getString(R.string.lemurx_agent_resume_prompt));
        send();
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
        if (mListening) endVoice(false);
        hideKeyboard();
        List<Attach> files = new ArrayList<>(mPending);
        mPending.clear();
        renderAttach();
        String text = typed;
        mInput.setText("");
        showError(null);
        Message user = new Message(UUID.randomUUID().toString(), "user", text, files);
        for (Attach file : files) {
            LemurXFiles.remember(getContext(), file.name, file.kind, file.excerpt);
        }
        append(user);
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
                                        Message current = mStreaming;
                                        if (current == null) return;
                                        if (current.view == null) {
                                            append(current);
                                            beginType(current);
                                        } else if (!TextUtils.isEmpty(current.text)) {
                                            // 一轮里第二段文字（工具调用之后）：另起一条消息。
                                            Message next = new Message(messageId, "assistant", "");
                                            mStreaming = next;
                                            append(next);
                                            beginType(next);
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
                                        current.text += delta;
                                        showStatus(null);
                                        scheduleType();
                                    }

                                    @Override
                                    public void onTextEnd(String messageId) {
                                        if (gen != mRunGen || !thread.equals(mThreadId)) return;
                                        // 打字机还在追：追上后再渲 markdown，这里不抢。
                                        scheduleType();
                                    }

                                    @Override
                                    public void onToolCall(String toolName) {
                                        if (gen != mRunGen || !thread.equals(mThreadId)) return;
                                        // 游客没有手机登记，打开或阅读网页会空转到超时。立刻停下来要求登录。
                                        if (needsPhone(toolName) && LemurXAccount.aeroTicket() == null) {
                                            requireSignIn();
                                            return;
                                        }
                                        showStatus(getContext().getString(
                                                R.string.lemurx_muse_working, toolLabel(toolName)));
                                    }

                                    @Override
                                    public void onToolResult(String toolCallId, String content) {
                                        if (gen != mRunGen || !thread.equals(mThreadId)) return;
                                        takeWrittenFile(content);
                                        // 工具已经回来：不要还停在"browse web"，下一轮模型思考时换成思考中。
                                        showStatus(getContext().getString(
                                                R.string.lemurx_muse_thinking));
                                    }

                                    @Override
                                    public void onFinished() {
                                        if (gen != mRunGen || !thread.equals(mThreadId)) return;
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

    /**
     * 这一步要在手机上打开或阅读网页。搜索和纯文字回答不在这里，游客仍然可以聊。
     */
    private static boolean needsPhone(String name) {
        if (TextUtils.isEmpty(name)) return false;
        if (name.startsWith("browser_")) return true;
        switch (name) {
            case "browse":
            case "browse_web":
            case "open_page":
            case "read_page":
                return true;
            default:
                return false;
        }
    }

    /** 停掉这一轮，写明要登录，并马上弹出登录底栏。 */
    private void requireSignIn() {
        mRunGen++;
        MuseApi.Run run = mRun;
        if (run != null) run.cancel();
        finishRun(null);
        if (mThreadId == null) return;
        Message note =
                new Message(
                        UUID.randomUUID().toString(),
                        "assistant",
                        getContext().getString(R.string.lemurx_muse_sign_in_to_operate));
        append(note);
        mStickToBottom = true;
        scrollToBottom(true);
        persist();
        Activity activity = LemurXConsoleShell.activity();
        if (activity != null) LemurXSignIn.showFromBrowser(activity);
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
        if (current != null && current.view != null && TextUtils.isEmpty(current.text)) {
            if (current.view.getParent() instanceof ViewGroup) {
                ((ViewGroup) current.view.getParent()).removeView(current.view);
            }
            mMessages.remove(current);
            if (mTypeMessage == current) mTypeMessage = null;
            mWinStart = -1;
            mWinEnd = -1;
            mTopSpan = -1;
            mBotSpan = -1;
            scheduleWindow();
        } else if (current != null) {
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

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshAgentPages();
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

    /** 用户点了停止，或者换会话 / 关页面。 */
    void stop() {
        MuseApi.Run run = mRun;
        if (run == null) return;
        run.cancel();
        finishRun(null);
    }

    private void setRunning(boolean running) {
        mBusy = running;
        mSend.setImageResource(running ? R.drawable.lemurx_muse_ic_stop : R.drawable.lemurx_muse_ic_send);
        mSend.setColorFilter(MusePage.onAccent(getContext()), PorterDuff.Mode.SRC_IN);
        mSend.setContentDescription(getContext().getString(
                running ? R.string.lemurx_muse_stop : R.string.lemurx_muse_send));
        if (!mListening) mMic.setAlpha(running ? 0.35f : 1f);
        mAttach.setAlpha(running ? 0.35f : 1f);
    }

    // ---------------------------------------------------------------- 语音

    private void onMicClicked() {
        LemurXHaptics.click(mMic);
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
            mInput.setHint(R.string.lemurx_muse_input_hint);
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
        mInput.setHint(R.string.lemurx_muse_input_hint);
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
        mInput.setHint(R.string.lemurx_muse_input_hint);
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

    private void paintMic() {
        Context context = getContext();
        if (mListening) {
            mMic.setBackground(MusePage.rounded(MusePage.accent(context), 20, context));
            mMic.setColorFilter(MusePage.onAccent(context), PorterDuff.Mode.SRC_IN);
            mMic.setContentDescription(context.getString(R.string.lemurx_muse_voice_stop));
            mMic.setAlpha(1f);
        } else {
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

    // ---------------------------------------------------------------- 标签页上下文

    /**
     * 这个标签页正在看的网页，作为 AG-UI context 交给服务端拼进系统提示；没有网页返回 null。
     * 网址只是上下文，页面本身的内容是不可信数据，这一点由服务端的提示词交代。
     */
    private @Nullable JSONArray tabContext() {
        String web = LemurXConsoleShell.tabWebUrl(mTab);
        if (web == null) return null;
        try {
            JSONObject ctx = new JSONObject();
            ctx.put("description", "Web page open in this tab");
            ctx.put("value", web);
            return new JSONArray().put(ctx);
        } catch (JSONException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 附件

    /** 这一轮要发给模型的一个附件。图片是 JPEG 的 Base64，摘录是已经截断的文本。 */
    private static final class Attach {
        final String name;
        final String excerpt;
        final List<String> images;

        final String kind;

        /** 模型交回的 PDF / Word。正文是 Markdown，文件由手机排出来。 */
        final boolean generated;

        /** 排好的 PDF 或 docx。预览页在 images 里。 */
        @Nullable byte[] data;

        boolean rendering;

        Attach(String name, String excerpt, List<String> images, String kind) {
            this(name, excerpt, images, kind, false);
        }

        Attach(String name, String excerpt, List<String> images, String kind, boolean generated) {
            this.name = name;
            this.excerpt = excerpt;
            this.images = images;
            this.kind = kind;
            this.generated = generated;
        }
    }

    private void onAttachClicked() {
        LemurXHaptics.click(mAttach);
        if (mBusy || mAttachReading) return;
        if (mPending.size() >= 4) {
            showError(getContext().getString(R.string.lemurx_muse_attach_full));
            return;
        }
        WindowAndroid window = LemurXConsoleShell.windowAndroid();
        if (window == null) {
            showError(getContext().getString(R.string.lemurx_muse_attach_failed));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(
                Intent.EXTRA_MIME_TYPES,
                new String[] {
                    "image/*",
                    "application/pdf",
                    "text/*",
                    "application/json",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                });
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
        if (mime.startsWith("image/")) {
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
            JSONObject source = new JSONObject();
            source.put("type", "data");
            source.put("mimeType", "image/jpeg");
            source.put("value", shot);
            JSONObject image = new JSONObject();
            image.put("type", "image");
            image.put("source", source);
            JSONObject copy = new JSONObject(last.toString());
            copy.put("content", new JSONArray().put(text).put(image));
            JSONArray result = new JSONArray();
            for (int i = 0; i < messages.length() - 1; i++) result.put(messages.get(i));
            result.put(copy);
            return result;
        } catch (JSONException e) {
            return messages;
        }
    }

    /**
     * 用户附的图片接在网页截图后面。预算只留最新的几张时，先丢掉截图，留下用户自己选的图。
     * 文本摘录已经写在消息正文里，这里只补图片。
     */
    private JSONArray withAttachments(JSONArray messages, List<Attach> files) {
        List<String> images = new ArrayList<>();
        for (Attach file : files) {
            for (String image : file.images) {
                if (images.size() >= 2) break;
                images.add(image);
            }
            if (images.size() >= 2) break;
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
            for (String image : images) parts.put(imagePart(image));
            last.put("content", parts);
            return messages;
        } catch (JSONException e) {
            return messages;
        }
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
            Pattern.compile("\\[\\[aero-file:(image|pdf|text|doc):([^\\]]+)\\]\\]");

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
            if (!file.excerpt.isEmpty()) {
                String body = Base64.encodeToString(
                        file.excerpt.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                out.append("\n[[aero-body:").append(body).append("]]");
            }
        }
        return out.toString().trim();
    }

    private static final Pattern BODY_MARK = Pattern.compile("\\[\\[aero-body:([A-Za-z0-9+/=]+)\\]\\]");

    /** 发给模型的历史只留种类和文件名，正文留在存档里供预览。 */
    private static String storedContent(Message message) {
        StringBuilder out = new StringBuilder(message.text == null ? "" : message.text.trim());
        for (Attach file : message.files) {
            String name = file.name.replace("]", " ").replace("\n", " ").trim();
            if (name.isEmpty()) name = "file";
            if (out.length() > 0) out.append('\n');
            out.append("[[aero-file:").append(file.kind).append(':').append(name).append("]]");
        }
        return out.toString().trim();
    }

    private static Message decodeStored(String id, String role, String content) {
        if (!content.contains("[[aero-file:")) return new Message(id, role, content);
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
            Matcher body = BODY_MARK.matcher(tail);
            if (body.lookingAt()) {
                String encoded = body.group(1);
                if (encoded != null) {
                    try {
                        excerpt = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException e) {
                        excerpt = "";
                    }
                }
            }
            files.add(
                    new Attach(
                            name,
                            excerpt,
                            generated ? new ArrayList<>() : List.of(),
                            kind,
                            generated));
        }
        String visible =
                BODY_MARK.matcher(FILE_MARK.matcher(content).replaceAll(""))
                        .replaceAll("")
                        .replace("[[aero-office]]", "")
                        .trim();
        return new Message(id, role, visible, files);
    }

    /** 用户气泡只放写下的字。文件单独成卡片，不嵌进这段文字里。 */
    private void paintUser(TextView view, Message message) {
        view.setText(message.text);
        view.setVisibility(TextUtils.isEmpty(message.text) ? View.GONE : View.VISIBLE);
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

    /** 文件卡片挂在正文下面，单独一块，点整张卡片打开。 */
    private void bindFiles(Message message) {
        LinearLayout row = message.row;
        if (row == null) return;
        View existing = row.findViewWithTag("aero-files");
        if (message.files.isEmpty()) {
            if (existing != null) row.removeView(existing);
            return;
        }
        LinearLayout box;
        if (existing instanceof LinearLayout) {
            box = (LinearLayout) existing;
            box.removeAllViews();
        } else {
            box = new LinearLayout(getContext());
            box.setTag("aero-files");
            box.setOrientation(LinearLayout.VERTICAL);
            row.addView(box, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        boolean user = "user".equals(message.role);
        for (Attach file : message.files) box.addView(fileCard(file, user));
    }

    private View fileCard(Attach file, boolean user) {
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
        texts.addView(name);
        texts.addView(kind);
        TextView share = new TextView(context);
        share.setText(R.string.lemurx_muse_community_share);
        share.setTextColor(MusePage.muted(context));
        share.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        share.setPadding(dp(10), dp(4), 0, dp(4));
        share.setOnClickListener(v -> MuseCommunityView.shareWork(file.name, file.kind, file.excerpt));
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

    private String fileKindLabel(String kind) {
        if ("pdf".equals(kind)) return getContext().getString(R.string.lemurx_muse_file_pdf);
        if ("doc".equals(kind)) return getContext().getString(R.string.lemurx_muse_file_word);
        if ("image".equals(kind)) return getContext().getString(R.string.lemurx_muse_file_image);
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

    /** 点文件名。模型生成的 PDF / Word 先排好再打开，普通附件直接预览。 */
    private void openAttachment(Attach file) {
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

    private boolean dismissPreview() {
        View stage = mPreview;
        if (stage == null) return false;
        removeView(stage);
        mPreview = null;
        if (sPreviewOwner == this) sPreviewOwner = null;
        BackPressManager.lemurXConsoleStateChanged();
        return true;
    }

    private void persist() {
        if (mThreadId == null || !mLoaded) return;
        JSONArray messages = archiveJson();
        // 服务端对空的非 default 会话会删行。没消息就不要写，免得加载竞态把已有历史抹掉。
        if (messages.length() == 0) return;
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
                // 按下就开始允许这条拿焦点。拖动超过阈值说明是在滚动，再挡回去。
                mScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                mArmX = event.getX();
                mArmY = event.getY();
                mPressGen++;
                int gen = mPressGen;
                view.postDelayed(
                        () -> beginSelection(view, gen), ViewConfiguration.getLongPressTimeout());
                break;
            case MotionEvent.ACTION_MOVE:
                if (mSelecting != null) break;
                float dx = event.getX() - mArmX;
                float dy = event.getY() - mArmY;
                int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                if (dx * dx + dy * dy > (float) slop * slop) {
                    mPressGen++;
                    mScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
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
        mScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        Selection.setSelection(text, start, end);
        mSelecting = view;
        view.requestFocus();
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (view.startActionMode(selectionMenu(view), ActionMode.TYPE_FLOATING) == null) {
            mSelecting = null;
            Selection.removeSelection(text);
            disarm(view);
        }
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
