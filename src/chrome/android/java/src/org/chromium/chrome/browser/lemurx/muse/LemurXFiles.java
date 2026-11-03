// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.FileProvider;

import org.chromium.base.FileProviderUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 本机的文件架。生成的文档和发出去的附件副本记在这里，不跟聊天正文混在一行。 */
@NullMarked
final class LemurXFiles {
    /** 对话不能引用这份文件，索引仍留给用户自己看。 */
    static final String SHARE_OFF = "off";
    /** 对话只能看到文件名和很短的一段索引。 */
    static final String SHARE_INDEX = "index";
    /** 对话可以引用正文。敏感文件不要用这一档。 */
    static final String SHARE_BODY = "body";

    static final class Entry {
        final String name;
        final String kind;
        final String text;
        final long savedAt;
        final String share;

        Entry(String name, String kind, String text, long savedAt, String share) {
            this.name = name;
            this.kind = kind;
            this.text = text;
            this.savedAt = savedAt;
            this.share = share == null || share.isEmpty() ? SHARE_INDEX : share;
        }
    }

    private static final int MAX_ENTRIES = 80;

    private LemurXFiles() {}

    static void remember(Context context, String name, String kind, String text) {
        remember(context, name, kind, text, null);
    }

    /**
     * 记入本机索引。{@code share} 为空时，同名文件沿用原来的参考权限，新文件默认只留索引。
     */
    static void remember(Context context, String name, String kind, String text, @Nullable String share) {
        if (name == null || name.isEmpty()) return;
        String body = text == null ? "" : text;
        List<Entry> entries = new ArrayList<>(load(context));
        String kept = share;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (entry.name.equals(name) && entry.kind.equals(kind)) {
                if (kept == null) kept = entry.share;
                entries.remove(i);
            }
        }
        if (kept == null) kept = SHARE_INDEX;
        entries.add(0, new Entry(name, kind, body, System.currentTimeMillis(), kept));
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.size() - 1);
        write(context, entries);
    }

    static List<Entry> load(Context context) {
        List<Entry> entries = new ArrayList<>();
        File file = index(context);
        if (!file.isFile()) return entries;
        byte[] raw;
        try (FileInputStream in = new FileInputStream(file)) {
            raw = in.readAllBytes();
        } catch (Exception e) {
            return entries;
        }
        try {
            JSONArray array = new JSONArray(new String(raw, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "");
                if (name.isEmpty()) continue;
                entries.add(
                        new Entry(
                                name,
                                o.optString("kind", "text"),
                                o.optString("text", ""),
                                o.optLong("savedAt", 0L),
                                o.optString("share", SHARE_INDEX)));
            }
        } catch (JSONException e) {
            return entries;
        }
        return entries;
    }

    /** 改这份文件在对话里的参考权限。找不到时不动。 */
    static void setShare(Context context, String name, String kind, String share) {
        if (name == null || share == null) return;
        List<Entry> entries = new ArrayList<>(load(context));
        boolean found = false;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (!entry.name.equals(name) || !entry.kind.equals(kind == null ? "" : kind)) continue;
            entries.set(i, new Entry(entry.name, entry.kind, entry.text, entry.savedAt, share));
            found = true;
        }
        if (!found) return;
        write(context, entries);
    }

    /** 对话上下文里的文件索引。不引用的文件不出现。正文只在用户允许引用时附上，并且截断。 */
    static String agentIndex(Context context) {
        StringBuilder out = new StringBuilder();
        for (Entry entry : load(context)) {
            if (SHARE_OFF.equals(entry.share)) continue;
            out.append("- ").append(entry.name).append(" (").append(entry.kind).append(")");
            String body = entry.text == null ? "" : entry.text.trim();
            int cap = SHARE_BODY.equals(entry.share) ? 1500 : 80;
            if (!body.isEmpty()) {
                body = LemurXArchive.maskSecrets(body);
                if (body.length() > cap) body = body.substring(0, cap);
                out.append(SHARE_BODY.equals(entry.share) ? "\n" : " — ").append(body);
            }
            out.append('\n');
            if (out.length() > 4000) break;
        }
        return out.toString().trim();
    }

    private static void write(Context context, List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", entry.name);
                o.put("kind", entry.kind);
                o.put("text", entry.text);
                o.put("savedAt", entry.savedAt);
                o.put("share", entry.share);
                array.put(o);
            } catch (JSONException e) {
                // 字段都是字符串和数字
            }
        }
        File file = index(context);
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(array.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 文件架写失败时聊天里的卡片还在
        }
    }

    /** 用当时的正文做成文件交给手机上的阅读器。社区里点开别人分享的产出也走这里。 */
    static void open(Context context, String name, String kind, String text) {
        try {
            byte[] bytes;
            String mime;
            if ("pdf".equals(kind)) {
                bytes = LemurXDocument.pdf(name, text);
                mime = "application/pdf";
            } else if ("doc".equals(kind)) {
                bytes = LemurXDocument.docx(name, text);
                mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            } else if (text == null || text.isEmpty()) {
                return;
            } else {
                bytes = text.getBytes(StandardCharsets.UTF_8);
                mime = "text/plain";
            }
            String safe = name == null ? "notes" : name.replace('/', ' ').replace('\\', ' ').trim();
            if (safe.isEmpty()) safe = "notes";
            File dir = new File(context.getCacheDir(), "pdfs");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File out = new File(dir, safe);
            try (FileOutputStream stream = new FileOutputStream(out)) {
                stream.write(bytes);
            }
            android.net.Uri uri = FileProviderUtils.getContentUriFromFile(out);
            if (uri == null) {
                uri = FileProvider.getUriForFile(context, context.getPackageName() + ".FileProvider", out);
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            // 没有能打开的应用时，文件架和社区都只是不跳转
        }
    }

    private static File index(Context context) {
        return LemurXAccount.localFile(context, "aero-files", "index.json");
    }
}
