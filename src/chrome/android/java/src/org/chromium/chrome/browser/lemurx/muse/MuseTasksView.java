// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.lemurx.LemurXConsoleShell;
import org.chromium.chrome.browser.tab.Tab;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 任务：每个标签页里的智能体正在做什么，一键接管。
 *
 * <p>两类条目合在一起、按时间倒序：
 *
 * <ul>
 *   <li>正在回复中的对话（{@link MuseChatView#liveStatus}），来自本进程里打开的标签页；
 *   <li>服务端的任务（{@code GET /api/agent} 的 tasks）：对话里委派出去、在后台跑的工作，带
 *       {@code threadId} 的能对应回发起它的标签页。
 * </ul>
 *
 * <p>筛选只有 全部 / 进行中 / 已完成；本标签页的条目排在最前。「接管」= 切到那个标签页（没开着就按
 * 会话 id 重新开一个），任务卡片上另有 暂停 / 继续 / 取消 / 重试 / 回答。页面可见期间每 3 秒刷新一次。
 */
@NullMarked
class MuseTasksView extends FrameLayout {
    private static final long POLL_MS = 3000;
    private static final Set<String> FINISHED =
            new HashSet<>(Arrays.asList("succeeded", "failed", "cancelled"));

    private static final int FILTER_ALL = 0;
    private static final int FILTER_ACTIVE = 1;
    private static final int FILTER_DONE = 2;

    private final MusePage mPage;
    private final LinearLayout mList;
    private final TextView[] mChips = new TextView[3];
    private final TextView mError;
    private int mFilter = FILTER_ALL;
    private @Nullable JSONArray mTasks;
    private boolean mPolling;
    private boolean mRequestInFlight;
    private final Runnable mPoll = this::poll;

    MuseTasksView(Context context, MusePage page) {
        super(context);
        mPage = page;
        int pad = MusePage.pagePadding(context);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(pad, dp(4), pad, dp(10));
        int[] labels = {
            R.string.lemurx_muse_filter_all,
            R.string.lemurx_muse_filter_active,
            R.string.lemurx_muse_filter_done,
        };
        for (int i = 0; i < 3; i++) {
            final int filter = i;
            TextView chip = new TextView(context);
            chip.setText(labels[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            chip.setOnClickListener(
                    v -> {
                        mFilter = filter;
                        styleChips();
                        render();
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            chips.addView(chip, lp);
            mChips[i] = chip;
        }
        styleChips();
        column.addView(chips);

        mError = new TextView(context);
        mError.setTextColor(0xFFB3261E);
        mError.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mError.setPadding(pad, 0, pad, dp(8));
        mError.setVisibility(View.GONE);
        column.addView(mError);

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        mList = new LinearLayout(context);
        mList.setOrientation(LinearLayout.VERTICAL);
        mList.setPadding(pad, 0, pad, pad);
        scroll.addView(mList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        render();
    }

    private int dp(int value) {
        return MusePage.dp(getContext(), value);
    }

    private void styleChips() {
        Context context = getContext();
        for (int i = 0; i < mChips.length; i++) {
            boolean on = i == mFilter;
            mChips[i].setTextColor(on ? 0xFFFFFFFF : MusePage.COLOR_TEXT);
            mChips[i].setBackground(on
                    ? MusePage.rounded(MusePage.COLOR_ACCENT, 18, context)
                    : MusePage.rounded(MusePage.COLOR_SURFACE, 18, context));
        }
    }

    // ---------------------------------------------------------------- 轮询

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startPolling();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPolling();
        super.onDetachedFromWindow();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == View.VISIBLE) {
            if (isAttachedToWindow()) startPolling();
        } else {
            stopPolling();
        }
    }

    private void startPolling() {
        if (mPolling || getVisibility() != View.VISIBLE) return;
        mPolling = true;
        poll();
    }

    private void stopPolling() {
        mPolling = false;
        removeCallbacks(mPoll);
    }

    void refreshNow() {
        removeCallbacks(mPoll);
        if (isAttachedToWindow()) {
            mPolling = true;
            poll();
        }
    }

    private void poll() {
        if (!mPolling) return;
        render(); // 本地的"回复中"状态不用等服务器
        if (mRequestInFlight) {
            postDelayed(mPoll, POLL_MS);
            return;
        }
        mRequestInFlight = true;
        MuseApi.get()
                .get(
                        "/api/agent",
                        (value, error) -> {
                            mRequestInFlight = false;
                            if (error != null) {
                                mError.setText(error);
                                mError.setVisibility(View.VISIBLE);
                            } else {
                                mError.setVisibility(View.GONE);
                                mTasks = value instanceof JSONObject
                                        ? ((JSONObject) value).optJSONArray("tasks")
                                        : null;
                                render();
                            }
                            if (mPolling) postDelayed(mPoll, POLL_MS);
                        });
    }

    // ---------------------------------------------------------------- 渲染

    private static final class Item {
        final @Nullable JSONObject task;
        final @Nullable String liveThread;
        final @Nullable String liveStatus;
        final String sortKey;

        Item(JSONObject task) {
            this.task = task;
            this.liveThread = null;
            this.liveStatus = null;
            this.sortKey = task.optString("updatedAt", "");
        }

        Item(String thread, String status) {
            this.task = null;
            this.liveThread = thread;
            this.liveStatus = status;
            this.sortKey = "~"; // 排在最前
        }

        @Nullable String thread() {
            if (liveThread != null) return liveThread;
            String t = task != null ? task.optString("threadId", "") : "";
            return t.isEmpty() ? null : t;
        }
    }

    private void render() {
        ThreadUtils.assertOnUiThread();
        Context context = getContext();
        List<Item> items = new ArrayList<>();
        Set<String> liveThreads = new HashSet<>();
        if (mFilter != FILTER_DONE) {
            for (Tab tab : LemurXConsoleShell.chatTabs()) {
                String thread = LemurXConsoleShell.threadOfTab(tab);
                if (thread == null || liveThreads.contains(thread)) continue;
                String status = MuseChatView.liveStatus(thread);
                if (status == null) continue;
                liveThreads.add(thread);
                items.add(new Item(thread, status));
            }
        }
        JSONArray tasks = mTasks;
        if (tasks != null) {
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task == null) continue;
                boolean done = FINISHED.contains(task.optString("status", ""));
                if (mFilter == FILTER_ACTIVE && done) continue;
                if (mFilter == FILTER_DONE && !done) continue;
                items.add(new Item(task));
            }
        }
        String mine = mPage.threadId();
        items.sort(
                (a, b) -> {
                    boolean am = mine != null && mine.equals(a.thread());
                    boolean bm = mine != null && mine.equals(b.thread());
                    if (am != bm) return am ? -1 : 1;
                    return b.sortKey.compareTo(a.sortKey);
                });

        mList.removeAllViews();
        if (items.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(mTasks == null && mError.getVisibility() == View.VISIBLE
                    ? R.string.lemurx_muse_tasks_offline
                    : R.string.lemurx_muse_tasks_empty);
            empty.setTextColor(MusePage.COLOR_MUTED);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(0, 1.3f);
            empty.setPadding(dp(24), dp(48), dp(24), dp(24));
            mList.addView(empty);
            return;
        }
        for (Item item : items) {
            mList.addView(item.task != null ? taskCard(item.task) : liveCard(item), cardLp());
        }
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        return lp;
    }

    private LinearLayout card() {
        Context context = getContext();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(MusePage.rounded(MusePage.COLOR_SURFACE, 18, context));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        return card;
    }

    private TextView text(CharSequence value, int sp, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView button(int label, boolean primary, View.OnClickListener onClick) {
        Context context = getContext();
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(primary ? 0xFFFFFFFF : MusePage.COLOR_TEXT);
        view.setBackground(primary
                ? MusePage.rounded(MusePage.COLOR_ACCENT, 16, context)
                : MusePage.outlined(0xFFC9CED3, 16, context));
        view.setPadding(dp(14), dp(7), dp(14), dp(7));
        view.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        lp.topMargin = dp(10);
        view.setLayoutParams(lp);
        return view;
    }

    /** "哪个标签页"那一行：有打开的标签页就显示它的标题。 */
    private @Nullable Tab tabFor(@Nullable String thread) {
        if (thread == null) return null;
        for (Tab tab : LemurXConsoleShell.chatTabs()) {
            if (thread.equals(LemurXConsoleShell.threadOfTab(tab))) return tab;
        }
        return null;
    }

    private String tabLine(@Nullable String thread, @Nullable Tab tab) {
        Context context = getContext();
        if (thread == null) return context.getString(R.string.lemurx_muse_tab_none);
        if (thread.equals(mPage.threadId())) return context.getString(R.string.lemurx_muse_tab_this);
        String title = tab != null ? tab.getTitle() : MuseChatView.titleOf(thread);
        if (TextUtils.isEmpty(title)) title = context.getString(R.string.lemurx_muse_brand);
        return context.getString(
                tab != null ? R.string.lemurx_muse_tab_open : R.string.lemurx_muse_tab_closed, title);
    }

    private void takeOver(@Nullable String thread) {
        if (thread == null) return;
        Tab tab = tabFor(thread);
        if (tab != null) {
            LemurXConsoleShell.switchToTab(tab);
        } else {
            mPage.openInNewTab(MuseUrls.chatUrl(thread));
        }
    }

    private View liveCard(Item item) {
        LinearLayout card = card();
        String thread = item.liveThread;
        Tab tab = tabFor(thread);
        String title = thread != null ? MuseChatView.titleOf(thread) : null;
        if (TextUtils.isEmpty(title) && tab != null) title = tab.getTitle();
        if (TextUtils.isEmpty(title)) title = getContext().getString(R.string.lemurx_muse_brand);
        card.addView(text(title, 15, MusePage.COLOR_TEXT, true));
        card.addView(statusLine(item.liveStatus != null ? item.liveStatus : "", 0xFF1A73E8));
        card.addView(text(tabLine(thread, tab), 13, MusePage.COLOR_MUTED, false));
        LinearLayout actions = new LinearLayout(getContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        if (thread != null && !thread.equals(mPage.threadId())) {
            actions.addView(button(R.string.lemurx_muse_take_over, true, v -> takeOver(thread)));
        }
        card.addView(actions);
        return card;
    }

    private TextView statusLine(String label, int color) {
        TextView view = text(label, 13, color, true);
        view.setPadding(0, dp(4), 0, dp(2));
        return view;
    }

    private View taskCard(JSONObject task) {
        Context context = getContext();
        LinearLayout card = card();
        String id = task.optString("id", "");
        String status = task.optString("status", "");
        String thread = task.optString("threadId", "");
        if (thread.isEmpty()) thread = null;
        Tab tab = tabFor(thread);

        card.addView(text(task.optString("title", task.optString("prompt", "")), 15, MusePage.COLOR_TEXT, true));
        card.addView(statusLine(statusLabel(status), statusColor(status)));

        // 计划进度：n/m 步 · 当前步
        JSONArray plan = task.optJSONArray("plan");
        if (plan != null && plan.length() > 0) {
            int done = 0;
            String current = null;
            for (int i = 0; i < plan.length(); i++) {
                JSONObject step = plan.optJSONObject(i);
                if (step == null) continue;
                String s = step.optString("status", "");
                if ("succeeded".equals(s)) done++;
                if (current == null && ("running".equals(s) || "waiting".equals(s))) {
                    current = step.optString("title", "");
                }
            }
            String progress = context.getString(R.string.lemurx_muse_steps, done, plan.length());
            if (!TextUtils.isEmpty(current)) progress += " · " + current;
            card.addView(text(progress, 13, MusePage.COLOR_MUTED, false));
        }
        String question = task.optString("question", "");
        if (!question.isEmpty() && "waiting_input".equals(status)) {
            TextView q = text(question, 14, MusePage.COLOR_TEXT, false);
            q.setPadding(0, dp(6), 0, 0);
            card.addView(q);
        }
        String result = task.optString("result", "");
        if (!result.isEmpty() && "succeeded".equals(status)) {
            TextView r = text(result.length() > 200 ? result.substring(0, 200) + "…" : result, 13,
                    MusePage.COLOR_MUTED, false);
            r.setPadding(0, dp(6), 0, 0);
            card.addView(r);
        }
        String error = task.optString("error", "");
        if (!error.isEmpty() && "failed".equals(status)) {
            TextView e = text(error, 13, 0xFFB3261E, false);
            e.setPadding(0, dp(6), 0, 0);
            card.addView(e);
        }
        TextView where = text(tabLine(thread, tab), 13, MusePage.COLOR_MUTED, false);
        where.setPadding(0, dp(6), 0, 0);
        card.addView(where);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        final String t = thread;
        if (t != null && !t.equals(mPage.threadId())) {
            actions.addView(button(R.string.lemurx_muse_take_over, true, v -> takeOver(t)));
        }
        switch (status) {
            case "queued":
            case "running":
            case "scheduled":
                actions.addView(button(R.string.lemurx_muse_pause, false, v -> control(id, "pause")));
                actions.addView(button(R.string.lemurx_muse_cancel, false, v -> control(id, "cancel")));
                break;
            case "paused":
                actions.addView(button(R.string.lemurx_muse_resume, false, v -> control(id, "resume")));
                actions.addView(button(R.string.lemurx_muse_cancel, false, v -> control(id, "cancel")));
                break;
            case "waiting_input":
                actions.addView(button(R.string.lemurx_muse_answer, false, v -> answer(id, question)));
                actions.addView(button(R.string.lemurx_muse_cancel, false, v -> control(id, "cancel")));
                break;
            case "waiting_approval":
                actions.addView(button(R.string.lemurx_muse_cancel, false, v -> control(id, "cancel")));
                break;
            case "failed":
                actions.addView(button(R.string.lemurx_muse_retry, false, v -> control(id, "retry")));
                break;
            default:
                break;
        }
        card.addView(actions);
        return card;
    }

    private String statusLabel(String status) {
        Context context = getContext();
        switch (status) {
            case "queued":
                return context.getString(R.string.lemurx_muse_status_queued);
            case "running":
                return context.getString(R.string.lemurx_muse_status_running);
            case "waiting_approval":
                return context.getString(R.string.lemurx_muse_status_waiting_approval);
            case "waiting_input":
                return context.getString(R.string.lemurx_muse_status_waiting_input);
            case "scheduled":
                return context.getString(R.string.lemurx_muse_status_scheduled);
            case "paused":
                return context.getString(R.string.lemurx_muse_status_paused);
            case "succeeded":
                return context.getString(R.string.lemurx_muse_status_succeeded);
            case "failed":
                return context.getString(R.string.lemurx_muse_status_failed);
            case "cancelled":
                return context.getString(R.string.lemurx_muse_status_cancelled);
            default:
                return status;
        }
    }

    private static int statusColor(String status) {
        switch (status) {
            case "running":
            case "queued":
            case "scheduled":
                return 0xFF1A73E8;
            case "waiting_approval":
            case "waiting_input":
            case "paused":
                return 0xFFB26A00;
            case "failed":
                return 0xFFB3261E;
            case "succeeded":
                return 0xFF1E7B34;
            default:
                return MusePage.COLOR_MUTED;
        }
    }

    // ---------------------------------------------------------------- 动作

    private void control(String id, String action) {
        JSONObject body = new JSONObject();
        try {
            body.put("action", action);
        } catch (JSONException e) {
            return;
        }
        MuseApi.get()
                .send(
                        "POST",
                        "/api/agent/tasks/" + id + "/control",
                        body,
                        (value, error) -> {
                            if (error != null) {
                                mError.setText(error);
                                mError.setVisibility(View.VISIBLE);
                            }
                            refreshNow();
                        });
    }

    private void answer(String id, String question) {
        Context context = getContext();
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(2);
        FrameLayout wrap = new FrameLayout(context);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(context)
                .setTitle(R.string.lemurx_muse_answer)
                .setMessage(question)
                .setView(wrap)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        R.string.lemurx_muse_send,
                        (dialog, which) -> {
                            String text = input.getText().toString().trim();
                            if (text.isEmpty()) return;
                            JSONObject body = new JSONObject();
                            try {
                                body.put("answer", text);
                            } catch (JSONException e) {
                                return;
                            }
                            MuseApi.get()
                                    .send("POST", "/api/agent/tasks/" + id + "/input", body,
                                            (value, error) -> {
                                                if (error != null) {
                                                    mError.setText(error);
                                                    mError.setVisibility(View.VISIBLE);
                                                }
                                                refreshNow();
                                            });
                        })
                .show();
    }

    void destroy() {
        stopPolling();
    }
}
