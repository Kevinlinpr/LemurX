// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.View;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 够用的 Markdown → Spanned：标题、粗体、斜体、删除线、行内代码、代码块、无序 / 有序 /
 * 任务列表、引用、链接。表格由 {@link MuseRich} 单独排成可以横滑的格子，不在这里铺管道符。
 * 流式回复每来一段都要重排一次，所以只做单遍扫描、不建语法树。
 *
 * <p>链接是模型生成的、不可信：只放行 http(s)，点击交给 {@link LinkHandler}（开新标签页），
 * 绝不在这里执行任何东西。
 */
@NullMarked
final class MuseMarkdown {
    interface LinkHandler {
        void open(String url);
    }

    private static final Pattern INLINE =
            Pattern.compile(
                    "(\\*\\*|__)(.+?)\\1" // 1,2 粗体
                            + "|(?<![\\w*])(\\*|_)(?!\\s)(.+?)(?<!\\s)\\3(?![\\w*])" // 3,4 斜体
                            + "|`([^`\\n]+)`" // 5 行内代码
                            + "|\\[([^\\]\\n]+)\\]\\((https?://[^\\s)]+)\\)" // 6,7 链接
                            + "|(?<![\\w/])(https?://[^\\s<>()\\[\\]\"']+)" // 8 裸链接
                            + "|~~([^~\\n]+)~~" // 9 删除线
                    );

    private static final int CODE_BG = 0x14132631;

    private MuseMarkdown() {}

    static Spanned render(String markdown, int textColor, @Nullable LinkHandler links) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = markdown.split("\n", -1);
        boolean inCode = false;
        int codeStart = 0;
        int ordinal = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                if (!inCode) {
                    inCode = true;
                    codeStart = out.length();
                } else {
                    inCode = false;
                    if (out.length() > codeStart) {
                        out.setSpan(new TypefaceSpan("monospace"), codeStart, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new BackgroundColorSpan(CODE_BG), codeStart, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new RelativeSizeSpan(0.92f), codeStart, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                continue;
            }
            if (inCode) {
                out.append(line);
                if (i < lines.length - 1) out.append('\n');
                continue;
            }
            int start = out.length();
            // 标题
            int level = 0;
            while (level < trimmed.length() && level < 4 && trimmed.charAt(level) == '#') level++;
            if (level > 0 && level < trimmed.length() && trimmed.charAt(level) == ' ') {
                ordinal = 0;
                appendInline(out, trimmed.substring(level + 1).trim(), links);
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(level == 1 ? 1.25f : level == 2 ? 1.15f : 1.05f),
                        start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.startsWith("- [ ] ") || trimmed.startsWith("- [x] ")
                    || trimmed.startsWith("- [X] ")) {
                ordinal = 0;
                boolean done = trimmed.charAt(3) == 'x' || trimmed.charAt(3) == 'X';
                out.append(done ? "☑  " : "☐  ");
                appendInline(out, trimmed.substring(6).trim(), links);
                if (done) {
                    out.setSpan(new StrikethroughSpan(), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                out.setSpan(new LeadingMarginSpan.Standard(0, 28), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ")) {
                ordinal = 0;
                out.append("•  ");
                appendInline(out, trimmed.substring(2).trim(), links);
                out.setSpan(new LeadingMarginSpan.Standard(0, 28), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (isOrdered(trimmed)) {
                int dot = trimmed.indexOf('.');
                ordinal++;
                out.append(trimmed.substring(0, dot + 1)).append(' ');
                appendInline(out, trimmed.substring(dot + 1).trim(), links);
                out.setSpan(new LeadingMarginSpan.Standard(0, 34), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.startsWith("> ")) {
                appendInline(out, trimmed.substring(2), links);
                out.setSpan(new QuoteSpan(0xFF9AA0A6, 6, 18), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.equals("---") || trimmed.equals("***")) {
                out.append("—————");
            } else {
                ordinal = 0;
                appendInline(out, line, links);
            }
            if (i < lines.length - 1) out.append('\n');
        }
        // 收尾：去掉末尾多余空行
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == '\n') end--;
        if (end < out.length()) out.delete(end, out.length());
        return out;
    }

    private static boolean isOrdered(String s) {
        int i = 0;
        while (i < s.length() && i < 3 && Character.isDigit(s.charAt(i))) i++;
        return i > 0 && i + 1 < s.length() && s.charAt(i) == '.' && s.charAt(i + 1) == ' ';
    }

    private static void appendInline(
            SpannableStringBuilder out, String text, @Nullable LinkHandler links) {
        Matcher m = INLINE.matcher(text);
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            last = m.end();
            int start = out.length();
            String bold = m.group(2);
            String italic = m.group(4);
            String code = m.group(5);
            String label = m.group(6);
            String href = m.group(7);
            String bare = m.group(8);
            String strike = m.group(9);
            if (bold != null) {
                appendInline(out, bold, links);
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (italic != null) {
                appendInline(out, italic, links);
                out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (code != null) {
                out.append(code);
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(CODE_BG), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (label != null && href != null) {
                out.append(label);
                link(out, start, href, links);
            } else if (strike != null) {
                appendInline(out, strike, links);
                out.setSpan(new StrikethroughSpan(), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (bare != null) {
                String url = bare;
                // 句尾标点不算进链接
                while (url.length() > 1 && ".,;:!?。，；：！？".indexOf(url.charAt(url.length() - 1)) >= 0) {
                    url = url.substring(0, url.length() - 1);
                }
                out.append(url);
                link(out, start, url, links);
                last = m.start() + url.length();
            }
        }
        out.append(text, last, text.length());
    }

    private static void link(
            SpannableStringBuilder out, int start, String url, @Nullable LinkHandler links) {
        if (links == null) return;
        out.setSpan(
                new ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        links.open(url);
                    }
                },
                start,
                out.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
