// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.FileProvider;

import org.chromium.base.FileProviderUtils;
import org.chromium.build.annotations.NullMarked;
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
    static final class Entry {
        final String name;
        final String kind;
        final String text;
        final long savedAt;

        Entry(String name, String kind, String text, long savedAt) {
            this.name = name;
            this.kind = kind;
            this.text = text;
            this.savedAt = savedAt;
        }
    }

    private static final int MAX_ENTRIES = 80;

    private LemurXFiles() {}

    static void remember(Context context, String name, String kind, String text) {
        if (name == null || name.isEmpty()) return;
        String body = text == null ? "" : text;
        List<Entry> entries = new ArrayList<>(load(context));
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (entry.name.equals(name) && entry.kind.equals(kind)) entries.remove(i);
        }
        entries.add(0, new Entry(name, kind, body, System.currentTimeMillis()));
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.size() - 1);
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", entry.name);
                o.put("kind", entry.kind);
                o.put("text", entry.text);
                o.put("savedAt", entry.savedAt);
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
                                o.optLong("savedAt", 0L)));
            }
        } catch (JSONException e) {
            return entries;
        }
        return entries;
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
        return new File(context.getFilesDir(), "aero-files/index.json");
    }
}
