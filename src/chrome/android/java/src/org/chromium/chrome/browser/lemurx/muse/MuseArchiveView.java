// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.chromium.base.Log;
import org.chromium.build.annotations.NullMarked;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.lemurx.LemurXConsoleShell;
import org.chromium.chrome.browser.lemurx.LemurXHaptics;
import org.chromium.build.annotations.Nullable;
import org.chromium.ui.base.WindowAndroid;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 档案库。通用信息和敏感信息分两页，文件索引和参考权限都在本机。 */
@NullMarked
final class MuseArchiveView extends FrameLayout {
    private static final String TAG = "LemurXArchive";

    private final LinearLayout mBody;
    private boolean mSensitive;
    private @Nullable JSONArray mNotes;
    private boolean mNotesLoading;
    private @Nullable String mNotesError;

    MuseArchiveView(android.content.Context context) {
        super(context);
        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = MusePage.dp(context, 16);
        column.setPadding(pad, MusePage.dp(context, 8), pad, MusePage.dp(context, 24));

        TextView hint = new TextView(context);
        hint.setText(R.string.lemurx_archive_hint);
        hint.setTextColor(MusePage.muted(context));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setLineSpacing(0, 1.25f);
        hint.setPadding(0, 0, 0, MusePage.dp(context, 12));
        column.addView(hint);

        LinearLayout tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        TextView general = tab(context, R.string.lemurx_archive_general, false);
        TextView sensitive = tab(context, R.string.lemurx_archive_sensitive, true);
        tabs.addView(general);
        tabs.addView(sensitive);
        column.addView(tabs);

        TextView upload = new TextView(context);
        upload.setText(R.string.lemurx_archive_upload);
        upload.setTextColor(MusePage.onAccent(context));
        upload.setTypeface(Typeface.DEFAULT_BOLD);
        upload.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        upload.setBackground(MusePage.rounded(MusePage.accent(context), 16, context));
        upload.setPadding(MusePage.dp(context, 14), MusePage.dp(context, 8),
                MusePage.dp(context, 14), MusePage.dp(context, 8));
        upload.setOnClickListener(v -> pickFile());
        TextView add = new TextView(context);
        add.setText(R.string.lemurx_archive_add);
        add.setTextColor(MusePage.text(context));
        add.setTypeface(Typeface.DEFAULT_BOLD);
        add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        add.setBackground(MusePage.rounded(MusePage.surface(context), 16, context));
        add.setPadding(MusePage.dp(context, 14), MusePage.dp(context, 8),
                MusePage.dp(context, 14), MusePage.dp(context, 8));
        add.setOnClickListener(v -> editFact(null));
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams uploadLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        uploadLp.rightMargin = MusePage.dp(context, 8);
        actions.addView(upload, uploadLp);
        actions.addView(add);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = MusePage.dp(context, 12);
        actionsLp.bottomMargin = MusePage.dp(context, 8);
        column.addView(actions, actionsLp);

        mBody = new LinearLayout(context);
        mBody.setOrientation(LinearLayout.VERTICAL);
        column.addView(mBody);
        scroll.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        general.setOnClickListener(v -> {
            mSensitive = false;
            paintTabs(general, sensitive);
            reload();
        });
        sensitive.setOnClickListener(v -> {
            mSensitive = true;
            paintTabs(general, sensitive);
            reload();
        });
        paintTabs(general, sensitive);
        reload();
        refreshNotes();
    }

    private void paintTabs(TextView general, TextView sensitive) {
        styleTab(general, !mSensitive);
        styleTab(sensitive, mSensitive);
    }

    private TextView tab(android.content.Context context, int label, boolean sensitive) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(MusePage.dp(context, 14), MusePage.dp(context, 8),
                MusePage.dp(context, 14), MusePage.dp(context, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = MusePage.dp(context, 8);
        view.setLayoutParams(lp);
        view.setTag(sensitive);
        return view;
    }

    private void styleTab(TextView view, boolean on) {
        android.content.Context context = getContext();
        view.setTextColor(on ? MusePage.onAccent(context) : MusePage.text(context));
        view.setBackground(MusePage.rounded(
                on ? MusePage.accent(context) : MusePage.surface(context), 16, context));
    }

    private void reload() {
        android.content.Context context = getContext();
        mBody.removeAllViews();
        String section = mSensitive ? LemurXArchive.SENSITIVE : LemurXArchive.GENERAL;
        List<LemurXArchive.Fact> facts = LemurXArchive.facts(context, section);
        if (facts.isEmpty() && !mSensitive) {
            mBody.addView(note(context, R.string.lemurx_archive_empty));
        }
        String previous = "";
        for (LemurXArchive.Fact fact : ordered(facts)) {
            if (!fact.kind.equals(previous)) {
                previous = fact.kind;
                mBody.addView(kindHeading(context, kindName(context, fact.kind)));
            }
            mBody.addView(factRow(context, fact));
        }
        if (mSensitive) {
            mBody.addView(note(context, R.string.lemurx_archive_vault));
            for (LemurXVault.Account account : LemurXVault.accounts()) {
                mBody.addView(vaultRow(context, account));
            }
            return;
        }
        TextView files = new TextView(context);
        files.setText(R.string.lemurx_archive_files);
        files.setTextColor(MusePage.text(context));
        files.setTypeface(Typeface.DEFAULT_BOLD);
        files.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        files.setPadding(0, MusePage.dp(context, 16), 0, MusePage.dp(context, 8));
        mBody.addView(files);
        List<LemurXFiles.Entry> entries = LemurXFiles.load(context);
        if (entries.isEmpty()) mBody.addView(note(context, R.string.lemurx_archive_files_empty));
        for (LemurXFiles.Entry entry : entries) mBody.addView(fileRow(context, entry));
        if (LemurXAccount.isSignedIn()) mBody.addView(notesBlock(context));
    }

    private TextView note(android.content.Context context, int text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(MusePage.muted(context));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setLineSpacing(0, 1.25f);
        view.setPadding(0, MusePage.dp(context, 8), 0, MusePage.dp(context, 8));
        return view;
    }

    private View factRow(android.content.Context context, LemurXArchive.Fact fact) {
        LinearLayout row = card(context);
        TextView title = new TextView(context);
        title.setText(fact.label);
        row.setOnClickListener(v -> editFact(fact));
        title.setTextColor(MusePage.text(context));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(title);
        TextView value = new TextView(context);
        boolean hidden = LemurXArchive.SENSITIVE.equals(fact.section);
        value.setText(hidden ? context.getString(R.string.lemurx_archive_hidden) : fact.value);
        value.setTextColor(MusePage.muted(context));
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        value.setPadding(0, MusePage.dp(context, 4), 0, 0);
        row.addView(value);
        if (hidden) {
            value.setOnClickListener(v -> {
                boolean showing = fact.value.contentEquals(value.getText());
                value.setText(showing ? context.getString(R.string.lemurx_archive_hidden) : fact.value);
            });
        }
        TextView remove = link(context, R.string.lemurx_archive_delete);
        remove.setOnClickListener(v -> {
            LemurXArchive.delete(context, fact.id);
            reload();
        });
        row.addView(remove);
        return row;
    }

    private View vaultRow(android.content.Context context, LemurXVault.Account account) {
        LinearLayout row = card(context);
        TextView title = new TextView(context);
        String who = account.username.isEmpty()
                ? context.getString(R.string.lemurx_login_username) : account.username;
        title.setText(account.host + " · " + who);
        title.setTextColor(MusePage.text(context));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(title);
        TextView hidden = new TextView(context);
        hidden.setText(R.string.lemurx_login_password_hidden);
        hidden.setTextColor(MusePage.muted(context));
        hidden.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hidden.setPadding(0, MusePage.dp(context, 4), 0, 0);
        row.addView(hidden);
        return row;
    }

    private View fileRow(android.content.Context context, LemurXFiles.Entry entry) {
        LinearLayout row = card(context);
        TextView name = new TextView(context);
        name.setText(entry.name);
        name.setTextColor(MusePage.text(context));
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setMaxLines(1);
        row.addView(name);
        LinearLayout choices = new LinearLayout(context);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        choices.setPadding(0, MusePage.dp(context, 8), 0, 0);
        choices.addView(shareChip(context, entry, LemurXFiles.SHARE_OFF, R.string.lemurx_archive_share_off));
        choices.addView(shareChip(context, entry, LemurXFiles.SHARE_INDEX, R.string.lemurx_archive_share_index));
        choices.addView(shareChip(context, entry, LemurXFiles.SHARE_BODY, R.string.lemurx_archive_share_body));
        row.addView(choices);
        return row;
    }

    private TextView shareChip(
            android.content.Context context, LemurXFiles.Entry entry, String share, int label) {
        TextView chip = new TextView(context);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        boolean on = share.equals(entry.share);
        chip.setTextColor(on ? MusePage.onAccent(context) : MusePage.text(context));
        chip.setBackground(MusePage.rounded(
                on ? MusePage.accent(context) : MusePage.surface(context), 12, context));
        chip.setPadding(MusePage.dp(context, 8), MusePage.dp(context, 6),
                MusePage.dp(context, 8), MusePage.dp(context, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = MusePage.dp(context, 6);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> {
            LemurXHaptics.tick(v);
            LemurXFiles.setShare(context, entry.name, entry.kind, share);
            reload();
        });
        return chip;
    }

    private LinearLayout card(android.content.Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(MusePage.dp(context, 12), MusePage.dp(context, 12),
                MusePage.dp(context, 12), MusePage.dp(context, 12));
        row.setBackground(MusePage.rounded(MusePage.surface(context), 16, context));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = MusePage.dp(context, 8);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView link(android.content.Context context, int label) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextColor(MusePage.link(context));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setPadding(0, MusePage.dp(context, 8), 0, 0);
        return view;
    }

    private static String kindName(android.content.Context context, String kind) {
        if (LemurXArchive.WORK.equals(kind)) return context.getString(R.string.lemurx_archive_kind_work);
        if (LemurXArchive.SOCIAL.equals(kind)) return context.getString(R.string.lemurx_archive_kind_social);
        if (LemurXArchive.PREFERENCE.equals(kind)) return context.getString(R.string.lemurx_archive_kind_preference);
        if (LemurXArchive.CONTACT.equals(kind) || kind == null || kind.isEmpty()) {
            return context.getString(R.string.lemurx_archive_kind_contact);
        }
        return kind;
    }

    private TextView kindHeading(android.content.Context context, String title) {
        TextView view = new TextView(context);
        view.setText(title);
        view.setTextColor(MusePage.muted(context));
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setPadding(MusePage.dp(context, 4), MusePage.dp(context, 10), 0, MusePage.dp(context, 4));
        return view;
    }

    private static List<LemurXArchive.Fact> ordered(List<LemurXArchive.Fact> facts) {
        Map<String, List<LemurXArchive.Fact>> groups = new LinkedHashMap<>();
        for (String kind : new String[] {
                LemurXArchive.CONTACT, LemurXArchive.SOCIAL, LemurXArchive.WORK, LemurXArchive.PREFERENCE}) {
            groups.put(kind, new ArrayList<>());
        }
        for (LemurXArchive.Fact fact : facts) {
            String kind = groups.containsKey(fact.kind) ? fact.kind : fact.kind;
            groups.computeIfAbsent(kind, k -> new ArrayList<>()).add(fact);
        }
        List<LemurXArchive.Fact> out = new ArrayList<>();
        for (List<LemurXArchive.Fact> group : groups.values()) {
            if (!group.isEmpty()) out.addAll(group);
        }
        return out;
    }

    private void editFact(LemurXArchive.@Nullable Fact existing) {
        android.content.Context context = getContext();
        boolean[] sensitive = new boolean[] {
            existing != null ? LemurXArchive.SENSITIVE.equals(existing.section) : mSensitive
        };
        String[] kind = new String[] {
            existing == null || !isBuiltinKind(existing.kind) ? LemurXArchive.CONTACT : existing.kind
        };
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = MusePage.dp(context, 20);
        form.setPadding(pad, MusePage.dp(context, 8), pad, 0);
        LinearLayout sections = new LinearLayout(context);
        sections.setOrientation(LinearLayout.HORIZONTAL);
        TextView general = choice(context, R.string.lemurx_archive_general);
        TextView secret = choice(context, R.string.lemurx_archive_sensitive);
        sections.addView(general);
        sections.addView(secret);
        form.addView(sections);
        LinearLayout kinds = new LinearLayout(context);
        kinds.setOrientation(LinearLayout.HORIZONTAL);
        kinds.setPadding(0, MusePage.dp(context, 8), 0, 0);
        TextView contact = choice(context, R.string.lemurx_archive_kind_contact);
        TextView social = choice(context, R.string.lemurx_archive_kind_social);
        TextView work = choice(context, R.string.lemurx_archive_kind_work);
        TextView preference = choice(context, R.string.lemurx_archive_kind_preference);
        kinds.addView(contact);
        kinds.addView(social);
        kinds.addView(work);
        kinds.addView(preference);
        form.addView(kinds);
        EditText custom = field(context, R.string.lemurx_archive_custom_kind, false);
        if (existing != null && !isBuiltinKind(existing.kind)) custom.setText(existing.kind);
        form.addView(custom);
        EditText label = field(context, R.string.lemurx_archive_label, false);
        if (existing != null) label.setText(existing.label);
        form.addView(label);
        EditText value = field(context, R.string.lemurx_archive_value, true);
        if (existing != null) value.setText(existing.value);
        form.addView(value);
        Runnable paint = () -> {
            paintChoice(general, !sensitive[0]);
            paintChoice(secret, sensitive[0]);
            paintChoice(contact, LemurXArchive.CONTACT.equals(kind[0]));
            paintChoice(social, LemurXArchive.SOCIAL.equals(kind[0]));
            paintChoice(work, LemurXArchive.WORK.equals(kind[0]));
            paintChoice(preference, LemurXArchive.PREFERENCE.equals(kind[0]));
        };
        general.setOnClickListener(v -> { sensitive[0] = false; paint.run(); });
        secret.setOnClickListener(v -> { sensitive[0] = true; paint.run(); });
        contact.setOnClickListener(v -> { kind[0] = LemurXArchive.CONTACT; custom.setText(""); paint.run(); });
        social.setOnClickListener(v -> { kind[0] = LemurXArchive.SOCIAL; custom.setText(""); paint.run(); });
        work.setOnClickListener(v -> { kind[0] = LemurXArchive.WORK; custom.setText(""); paint.run(); });
        preference.setOnClickListener(v -> { kind[0] = LemurXArchive.PREFERENCE; custom.setText(""); paint.run(); });
        paint.run();
        ScrollView scroll = new ScrollView(context);
        scroll.addView(form);
        new AlertDialog.Builder(context)
                .setTitle(existing == null ? R.string.lemurx_archive_add : R.string.lemurx_archive_edit)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.lemurx_archive_save, (d, w) -> {
                    String written = custom.getText().toString().trim().replace("|", " ").replace("\n", " ");
                    if (written.length() > 16) written = written.substring(0, 16);
                    String useKind = written.isEmpty() ? kind[0] : written;
                    String useLabel = label.getText().toString().trim();
                    String useValue = value.getText().toString().trim();
                    if (useLabel.isEmpty() || useValue.isEmpty()) return;
                    String section = sensitive[0] ? LemurXArchive.SENSITIVE : LemurXArchive.GENERAL;
                    LemurXArchive.Proposal proposal = new LemurXArchive.Proposal(
                            section, useKind, useLabel, useValue, sensitive[0]);
                    if (existing == null) LemurXArchive.save(context, proposal, "you");
                    else LemurXArchive.update(context, existing.id, proposal, "you");
                    reload();
                })
                .show();
    }

    private static boolean isBuiltinKind(String kind) {
        return LemurXArchive.CONTACT.equals(kind)
                || LemurXArchive.SOCIAL.equals(kind)
                || LemurXArchive.WORK.equals(kind)
                || LemurXArchive.PREFERENCE.equals(kind);
    }

    private TextView choice(android.content.Context context, int label) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setPadding(MusePage.dp(context, 10), MusePage.dp(context, 6),
                MusePage.dp(context, 10), MusePage.dp(context, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = MusePage.dp(context, 6);
        lp.bottomMargin = MusePage.dp(context, 6);
        view.setLayoutParams(lp);
        return view;
    }

    private void paintChoice(TextView view, boolean on) {
        android.content.Context context = getContext();
        view.setTextColor(on ? MusePage.onAccent(context) : MusePage.text(context));
        view.setBackground(MusePage.rounded(
                on ? MusePage.accent(context) : MusePage.surface(context), 14, context));
    }

    private EditText field(android.content.Context context, int hint, boolean multi) {
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setTextColor(MusePage.text(context));
        input.setHintTextColor(MusePage.muted(context));
        input.setSingleLine(!multi);
        if (multi) input.setMinLines(2);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | (multi ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        return input;
    }

    private View notesBlock(android.content.Context context) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText(R.string.lemurx_muse_data_agent);
        title.setTextColor(MusePage.text(context));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setPadding(0, MusePage.dp(context, 16), 0, MusePage.dp(context, 4));
        box.addView(title);
        box.addView(note(context, R.string.lemurx_muse_data_agent_hint));
        if (mNotesError != null) {
            TextView error = new TextView(context);
            error.setText(mNotesError);
            error.setTextColor(MusePage.error(context));
            error.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            box.addView(error);
        }
        JSONArray items = mNotes;
        if (items == null || items.length() == 0) {
            box.addView(note(context, items == null && mNotesLoading
                    ? R.string.lemurx_muse_data_loading : R.string.lemurx_muse_data_empty));
        } else {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) box.addView(noteRow(context, item));
            }
        }
        TextView add = link(context, R.string.lemurx_muse_data_add);
        add.setOnClickListener(v -> editNote(null));
        box.addView(add);
        return box;
    }

    private View noteRow(android.content.Context context, JSONObject item) {
        LinearLayout row = card(context);
        TextView text = new TextView(context);
        text.setText(item.optString("text", ""));
        text.setTextColor(MusePage.text(context));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(text);
        row.setOnClickListener(v -> editNote(item));
        TextView remove = link(context, R.string.lemurx_archive_delete);
        remove.setOnClickListener(v -> forgetNote(item.optString("id", "")));
        row.addView(remove);
        return row;
    }

    private void refreshNotes() {
        if (!LemurXAccount.isSignedIn()) return;
        if (mNotesLoading) return;
        mNotesLoading = true;
        MuseApi.get().get("/api/agent", (value, error) -> {
            mNotesLoading = false;
            mNotesError = error;
            mNotes = value instanceof JSONObject ? ((JSONObject) value).optJSONArray("memories") : mNotes;
            if (!mSensitive) reload();
        });
    }

    private void editNote(@Nullable JSONObject item) {
        android.content.Context context = getContext();
        EditText input = field(context, R.string.lemurx_muse_data_add_hint, true);
        if (item != null) input.setText(item.optString("text", ""));
        FrameLayout wrap = new FrameLayout(context);
        wrap.setPadding(MusePage.dp(context, 20), MusePage.dp(context, 8), MusePage.dp(context, 20), 0);
        wrap.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(context)
                .setTitle(item == null ? R.string.lemurx_muse_data_add : R.string.lemurx_muse_data_edit)
                .setView(wrap)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.lemurx_archive_save, (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (text.isEmpty()) return;
                    JSONObject body = new JSONObject();
                    try {
                        body.put("text", text);
                        body.put("source", "You");
                    } catch (JSONException e) {
                        return;
                    }
                    String id = item == null ? "" : item.optString("id", "");
                    String path = id.isEmpty() ? "/api/agent/memories" : "/api/agent/memories/" + id;
                    MuseApi.get().send("POST", path, body, (value, error) -> {
                        mNotesError = error;
                        refreshNotes();
                    });
                })
                .show();
    }

    private void forgetNote(String id) {
        if (id.isEmpty()) return;
        MuseApi.get().send("POST", "/api/agent/memories/" + id + "/forget", new JSONObject(),
                (value, error) -> {
                    mNotesError = error;
                    refreshNotes();
                });
    }

    private void pickFile() {
        WindowAndroid window = LemurXConsoleShell.windowAndroid();
        if (window == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        window.showIntent(intent, (resultCode, data) -> {
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
            Uri uri = data.getData();
            String name = queryName(uri);
            String body = readText(uri);
            String kind = body.isEmpty() ? "file" : "text";
            post(() -> askShare(name, kind, body));
        }, null);
    }

    private void askShare(String name, String kind, String body) {
        android.content.Context context = getContext();
        LemurXFiles.remember(context, name, kind, body, LemurXFiles.SHARE_INDEX);
        List<LemurXArchive.Proposal> found = LemurXArchive.harvest(body);
        found.removeIf(item -> LemurXArchive.already(context, item));
        if (!found.isEmpty()) {
            new android.app.AlertDialog.Builder(context)
                    .setTitle(R.string.lemurx_archive)
                    .setMessage(context.getString(R.string.lemurx_archive_from_file, found.size()))
                    .setNegativeButton(R.string.lemurx_archive_skip, null)
                    .setPositiveButton(R.string.lemurx_archive_keep, (d, w) -> {
                        for (LemurXArchive.Proposal item : found) {
                            LemurXArchive.save(context, item, name);
                        }
                        reload();
                    })
                    .show();
        }
        reload();
    }

    private String queryName(Uri uri) {
        String name = "file";
        try (Cursor cursor = getContext().getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
            }
        } catch (Exception e) {
            Log.i(TAG, "name: %s", e.getMessage());
        }
        if (name == null || name.isEmpty()) name = "file";
        return name;
    }

    private String readText(Uri uri) {
        try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
            if (in == null) return "";
            byte[] raw = in.readNBytes(80_000);
            int zeros = 0;
            for (byte b : raw) if (b == 0) zeros++;
            if (zeros > raw.length / 20) return "";
            String text = new String(raw, StandardCharsets.UTF_8).trim();
            return text.length() > 8000 ? text.substring(0, 8000) : text;
        } catch (Exception e) {
            Log.i(TAG, "read: %s", e.getMessage());
            return "";
        }
    }
}
