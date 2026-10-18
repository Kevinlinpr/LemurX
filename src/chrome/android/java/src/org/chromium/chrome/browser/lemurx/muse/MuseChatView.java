// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
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
import org.chromium.chrome.browser.lemurx.LemurXKillSwitch;
import org.chromium.chrome.browser.tab.Tab;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 *   <li>空会话显示一句话 + 几条开场建议（有网页的标签页第一条是"总结当前网页"）；列表没贴底时
 *       右下角浮一个"回到最新"按钮。
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
        @Nullable TextView view;

        Message(String id, String role, String text) {
            this.id = id;
            this.role = role;
            this.text = text;
        }
    }

    /** 当前挂在哪个页面上；页面销毁后视图还活着（见 {@link MusePage} 的缓存），期间为 null。 */
    private @Nullable MusePage mPage;

    /** 视图所属的标签页；页面没挂着时也要能拿到（缓存就是按标签页存的），所以记一份。 */
    private final Tab mTab;
    private final ScrollView mScroll;
    private final LinearLayout mList;
    private final LinearLayout mEmpty;
    private final TextView mStatus;
    private final TextView mError;
    /** 列表末尾的"Agent 正在…"那一行：三个跳动的点 + 可选的说明文字（工具名）。 */
    private final LinearLayout mTyping;
    private final EditText mInput;
    private final ImageView mSend;
    private final ImageView mJump;
    private final LinearLayout mSuggestions;

    private final List<Message> mMessages = new ArrayList<>();
    private @Nullable String mThreadId;
    private boolean mLoading;
    private boolean mLoaded;
    private MuseApi.@Nullable Run mRun;
    private @Nullable Message mStreaming;
    /** 打字机：已经画到这条回复的第几个字符。落后于 {@link Message#text} 时逐帧往前赶。 */
    private int mTyped;
    private boolean mTypePending;
    private boolean mTypeFinished;
    private @Nullable Message mTypeMessage;
    private boolean mStickToBottom = true;
    private boolean mImeSuppressed;

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
        mScroll.getViewTreeObserver()
                .addOnScrollChangedListener(
                        () -> {
                            View child = mScroll.getChildAt(0);
                            if (child == null) return;
                            int bottom = child.getHeight() - mScroll.getHeight() - mScroll.getScrollY();
                            mStickToBottom = bottom < dp(80);
                            updateJump();
                        });
        FrameLayout listWrap = new FrameLayout(context);
        mList = new LinearLayout(context);
        mList.setOrientation(LinearLayout.VERTICAL);
        mList.setPadding(pad, dp(8), pad, dp(12));
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
        brand.setTextColor(MusePage.COLOR_TEXT);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        brand.setGravity(Gravity.CENTER);
        brand.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brandLp.topMargin = dp(16);
        mEmpty.addView(brand, brandLp);
        TextView hint = new TextView(context);
        hint.setText(R.string.lemurx_muse_empty_hint);
        hint.setTextColor(MusePage.COLOR_MUTED);
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
        mJump.setBackground(MusePage.rounded(0xFFFFFFFF, 20, context));
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
        MuseDotsView dots = new MuseDotsView(context, MusePage.COLOR_MUTED);
        mTyping.addView(dots);
        mStatus = new TextView(context);
        mStatus.setTextColor(MusePage.COLOR_MUTED);
        mStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.setMarginStart(dp(10));
        mTyping.addView(mStatus, statusLp);
        mError = new TextView(context);
        mError.setTextColor(0xFFB3261E);
        mError.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mError.setPadding(pad + dp(4), 0, pad, dp(6));
        mError.setVisibility(View.GONE);
        column.addView(mError);

        // ---- 输入栏
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.BOTTOM);
        bar.setBackground(MusePage.rounded(MusePage.COLOR_SURFACE, 26, context));
        bar.setPadding(dp(16), dp(6), dp(6), dp(6));
        mInput = new EditText(context);
        mInput.setHint(R.string.lemurx_muse_input_hint);
        mInput.setHintTextColor(MusePage.COLOR_MUTED);
        mInput.setTextColor(MusePage.COLOR_TEXT);
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
        bar.addView(mInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mSend = new ImageView(context);
        mSend.setImageResource(R.drawable.lemurx_muse_ic_send);
        mSend.setBackground(MusePage.rounded(MusePage.COLOR_ACCENT, 20, context));
        mSend.setContentDescription(context.getString(R.string.lemurx_muse_send));
        mSend.setClickable(true);
        // 不能抢焦点：一抢输入框失焦，键盘收起、底栏立刻回来，点按就落到药丸上。
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

    @Override
    protected void onDetachedFromWindow() {
        if (mImeSuppressed) {
            mImeSuppressed = false;
            LemurXConsoleShell.setNavSuppressed(this, false);
        }
        super.onDetachedFromWindow();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility != View.VISIBLE && mImeSuppressed) {
            mImeSuppressed = false;
            LemurXConsoleShell.setNavSuppressed(this, false);
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

    /** 切到某个会话：同一个就不动；换了会话时取消进行中的回复并重新拉历史。 */
    void load(String threadId) {
        if (threadId.equals(mThreadId)) return;
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
                            for (int i = 0; i < messages.length(); i++) {
                                JSONObject m = messages.optJSONObject(i);
                                if (m == null) continue;
                                String role = m.optString("role", "");
                                String content = m.optString("content", "");
                                if (!"user".equals(role) && !"assistant".equals(role)) continue;
                                if (TextUtils.isEmpty(content)) continue;
                                append(new Message(m.optString("id", UUID.randomUUID().toString()), role, content));
                            }
                            for (Message m : typed) append(m);
                            rememberTitle();
                            notifyTitleChanged();
                            scrollToBottom(false);
                        });
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

    /** 开场建议：有网页的标签页第一条是"总结当前网页"，其余固定几条。点一下就发出去。 */
    private void buildSuggestions() {
        Context context = getContext();
        mSuggestions.removeAllViews();
        List<String[]> items = new ArrayList<>();
        String web = LemurXConsoleShell.tabWebUrl(mTab);
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
        for (String[] item : items) {
            TextView chip = new TextView(context);
            chip.setText(item[0]);
            chip.setTextColor(MusePage.COLOR_TEXT);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(MusePage.rounded(MusePage.COLOR_SURFACE, 14, context));
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
        Context context = getContext();
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        view.setLineSpacing(0, 1.28f);
        view.setTextIsSelectable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if ("user".equals(message.role)) {
            view.setTextColor(MusePage.COLOR_TEXT);
            view.setBackground(MusePage.rounded(MusePage.COLOR_SURFACE, 20, context));
            view.setPadding(dp(14), dp(10), dp(14), dp(10));
            view.setText(message.text);
            lp.gravity = Gravity.END;
            lp.setMargins(dp(48), dp(10), 0, dp(10));
        } else {
            view.setTextColor(MusePage.COLOR_TEXT);
            view.setPadding(dp(2), dp(6), dp(2), dp(6));
            view.setMovementMethod(LinkMovementMethod.getInstance());
            view.setLinkTextColor(0xFF1A73E8);
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.setMargins(0, dp(6), 0, dp(6));
            renderInto(view, message.text);
        }
        message.view = view;
        mList.addView(view, lp);
        // 正在输入的那行始终压在列表最后。
        if (mTyping.getParent() == mList) attachTypingRow();
        updateEmpty();
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
        view.setText(MuseMarkdown.render(markdown, MusePage.COLOR_TEXT, this::openInNewTab));
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
            if (mTyped < have) {
                m.view.setText(shown);
            } else {
                renderInto(m.view, shown);
            }
            if (mStickToBottom) scrollToBottom(false);
            updateJump();
            scheduleType();
            return;
        }
        if (mTypeFinished && !TextUtils.isEmpty(m.text)) {
            renderInto(m.view, m.text);
            if (mStickToBottom) scrollToBottom(true);
            updateJump();
        }
    }

    private void scrollToBottom(boolean smooth) {
        mScroll.post(
                () -> {
                    // 不用 fullScroll：它会把焦点挪到列表里的文字上，输入框失焦、键盘缩回去。
                    View child = mScroll.getChildAt(0);
                    int bottom = child == null ? 0 : child.getHeight();
                    if (smooth) {
                        mScroll.smoothScrollTo(0, bottom);
                    } else {
                        mScroll.scrollTo(0, bottom);
                    }
                });
    }

    private void showError(@Nullable String error) {
        mError.setText(error == null ? "" : error);
        mError.setVisibility(error == null ? View.GONE : View.VISIBLE);
    }

    private void showStatus(@Nullable String status) {
        if (status == null) {
            if (mTyping.getParent() == mList) mList.removeView(mTyping);
        } else {
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
    }

    // ---------------------------------------------------------------- 发送 / 停止

    private void onSendClicked() {
        LemurXHaptics.click(mSend);
        if (mRun != null) {
            stop();
        } else {
            send();
        }
    }

    private void send() {
        if (LemurXKillSwitch.isDisabled()) return;
        if (mRun != null || mThreadId == null) return;
        String text = mInput.getText().toString().trim();
        if (text.isEmpty()) return;
        mInput.setText("");
        showError(null);
        Message user = new Message(UUID.randomUUID().toString(), "user", text);
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
        mRun =
                MuseApi.get()
                        .run(
                                thread,
                                withTabContext(toJson()),
                                tabContext(),
                                new MuseApi.RunListener() {
                                    @Override
                                    public void onTextStart(String messageId) {
                                        if (!thread.equals(mThreadId)) return;
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
                                        if (!thread.equals(mThreadId)) return;
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
                                        if (!thread.equals(mThreadId)) return;
                                        // 打字机还在追：追上后再渲 markdown，这里不抢。
                                        scheduleType();
                                    }

                                    @Override
                                    public void onToolCall(String toolName) {
                                        if (!thread.equals(mThreadId)) return;
                                        showStatus(getContext().getString(
                                                R.string.lemurx_muse_working, toolLabel(toolName)));
                                    }

                                    @Override
                                    public void onToolResult(String toolCallId, String content) {
                                        if (!thread.equals(mThreadId)) return;
                                        // 工具已经回来：不要还停在"browse web"，下一轮模型思考时换成思考中。
                                        showStatus(getContext().getString(
                                                R.string.lemurx_muse_thinking));
                                    }

                                    @Override
                                    public void onFinished() {
                                        if (!thread.equals(mThreadId)) return;
                                        finishRun(null);
                                    }

                                    @Override
                                    public void onError(String message) {
                                        if (!thread.equals(mThreadId)) return;
                                        finishRun(message);
                                    }
                                });
    }

    private void finishRun(@Nullable String error) {
        mRun = null;
        Message current = mStreaming;
        mStreaming = null;
        setRunning(false);
        showStatus(null);
        if (current != null && current.view != null && TextUtils.isEmpty(current.text)) {
            mList.removeView(current.view);
            mMessages.remove(current);
            if (mTypeMessage == current) mTypeMessage = null;
        } else if (current != null) {
            mTypeFinished = true;
            if (mTypeMessage == null) mTypeMessage = current;
            scheduleType();
        }
        if (error != null) showError(error);
        persist();
        if (mStickToBottom) scrollToBottom(true);
    }

    /** 用户点了停止，或者换会话 / 关页面。 */
    void stop() {
        MuseApi.Run run = mRun;
        if (run == null) return;
        run.cancel();
        finishRun(null);
    }

    private void setRunning(boolean running) {
        mSend.setImageResource(running ? R.drawable.lemurx_muse_ic_stop : R.drawable.lemurx_muse_ic_send);
        mSend.setContentDescription(getContext().getString(
                running ? R.string.lemurx_muse_stop : R.string.lemurx_muse_send));
    }

    private String toolLabel(String name) {
        Context context = getContext();
        switch (name) {
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

    // ---------------------------------------------------------------- 持久化

    private JSONArray toJson() {
        JSONArray array = new JSONArray();
        for (Message m : mMessages) {
            if (TextUtils.isEmpty(m.text)) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("role", m.role);
                o.put("content", m.text);
                array.put(o);
            } catch (JSONException e) {
                // 不会发生：都是字符串
            }
        }
        return array;
    }

    private void persist() {
        if (mThreadId == null || !mLoaded) return;
        JSONObject body = new JSONObject();
        try {
            body.put("id", mThreadId);
            body.put("messages", toJson());
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
        stop();
        if (mThreadId != null) sLive.remove(mThreadId);
        ThreadUtils.assertOnUiThread();
    }
}
