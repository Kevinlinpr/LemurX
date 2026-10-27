// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 助手回复的排版。段落、标题、列表仍走 {@link MuseMarkdown}；表格和代码块单独成一块，
 * 这样管道符不会原样铺在对话里，宽表可以横着滑。
 *
 * <p>流式输出时按已经写完的行排，半截标记不会露出来。正在打的那一段末尾留一根细光标。
 */
@NullMarked
final class MuseRich {
    interface Styler {
        void apply(TextView view);
    }

    private static final int KIND = 0;
    private static final int SIG = 1;

    private MuseRich() {}

    static void show(
            LinearLayout host,
            String markdown,
            boolean streaming,
            MuseMarkdown.@Nullable LinkHandler links,
            Styler styler) {
        List<Block> blocks = parse(markdown == null ? "" : markdown, streaming);
        boolean same = host.getChildCount() == blocks.size();
        if (same) {
            for (int i = 0; i < blocks.size(); i++) {
                Object tag = host.getChildAt(i).getTag();
                if (!(tag instanceof String[]) || !blocks.get(i).kind.equals(((String[]) tag)[KIND])) {
                    same = false;
                    break;
                }
            }
        }
        if (!same) {
            host.removeAllViews();
            for (Block block : blocks) {
                host.addView(create(host, block, links, styler), gap(host));
            }
            return;
        }
        for (int i = 0; i < blocks.size(); i++) {
            update(host, host.getChildAt(i), blocks.get(i), links, styler);
        }
    }

    private static LinearLayout.LayoutParams gap(LinearLayout host) {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(host, 8);
        return lp;
    }

    private static View create(
            LinearLayout host,
            Block block,
            MuseMarkdown.@Nullable LinkHandler links,
            Styler styler) {
        View view;
        if ("code".equals(block.kind)) view = codeView(host, block, styler);
        else if ("table".equals(block.kind)) view = tableView(host, block, links, styler);
        else if ("rule".equals(block.kind)) view = ruleView(host);
        else view = textView(host, block, links, styler);
        view.setTag(new String[] {block.kind, block.sig});
        return view;
    }

    private static void update(
            LinearLayout host,
            View child,
            Block block,
            MuseMarkdown.@Nullable LinkHandler links,
            Styler styler) {
        String[] tag = (String[]) child.getTag();
        if (tag != null && block.sig.equals(tag[SIG])) return;
        child.setTag(new String[] {block.kind, block.sig});
        if ("code".equals(block.kind) && child instanceof TextView) {
            ((TextView) child).setText(block.text);
            return;
        }
        if ("text".equals(block.kind) && child instanceof TextView) {
            ((TextView) child).setText(span(host, block, links));
            return;
        }
        if ("table".equals(block.kind)) {
            ViewGroup parent = (ViewGroup) child.getParent();
            int index = parent.indexOfChild(child);
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            parent.removeViewAt(index);
            View next = create(host, block, links, styler);
            parent.addView(next, index, lp);
        }
    }

    private static TextView textView(
            LinearLayout host,
            Block block,
            MuseMarkdown.@Nullable LinkHandler links,
            Styler styler) {
        TextView view = new TextView(host.getContext());
        view.setTextColor(MusePage.text(host.getContext()));
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        view.setLineSpacing(dp(host, 2), 1.15f);
        view.setText(span(host, block, links));
        styler.apply(view);
        return view;
    }

    private static Spanned span(
            LinearLayout host, Block block, MuseMarkdown.@Nullable LinkHandler links) {
        SpannableStringBuilder out =
                new SpannableStringBuilder(MuseMarkdown.render(block.text, MusePage.text(host.getContext()), links));
        if (block.caret) {
            int start = out.length();
            out.append(" ▍");
            out.setSpan(
                    new ForegroundColorSpan(MusePage.muted(host.getContext())),
                    start,
                    out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    private static TextView codeView(LinearLayout host, Block block, Styler styler) {
        TextView view = new TextView(host.getContext());
        view.setText(block.text);
        view.setTextColor(MusePage.text(host.getContext()));
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTypeface(Typeface.MONOSPACE);
        view.setLineSpacing(dp(host, 2), 1f);
        int pad = dp(host, 12);
        view.setPadding(pad, pad, pad, pad);
        view.setBackground(MusePage.rounded(MusePage.surface(host.getContext()), 12, host.getContext()));
        styler.apply(view);
        return view;
    }

    private static View ruleView(LinearLayout host) {
        View rule = new View(host.getContext());
        rule.setBackgroundColor(MusePage.muted(host.getContext()));
        rule.setMinimumHeight(Math.max(1, dp(host, 1)));
        return rule;
    }

    private static View tableView(
            LinearLayout host,
            Block block,
            MuseMarkdown.@Nullable LinkHandler links,
            Styler styler) {
        HorizontalScrollView scroll = new HorizontalScrollView(host.getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.setBackground(MusePage.rounded(MusePage.surface(host.getContext()), 12, host.getContext()));
        int pad = dp(host, 4);
        scroll.setPadding(pad, pad, pad, pad);
        LinearLayout grid = new LinearLayout(host.getContext());
        grid.setOrientation(LinearLayout.VERTICAL);
        boolean header = block.header;
        for (int r = 0; r < block.rows.size(); r++) {
            String[] cells = block.rows.get(r);
            LinearLayout line = new LinearLayout(host.getContext());
            line.setOrientation(LinearLayout.HORIZONTAL);
            if (header && r == 0) {
                line.setBackgroundColor(0x14000000);
            }
            for (String cell : cells) {
                TextView text = new TextView(host.getContext());
                text.setText(MuseMarkdown.render(cell, MusePage.text(host.getContext()), links));
                text.setTextColor(MusePage.text(host.getContext()));
                text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
                if (header && r == 0) text.setTypeface(Typeface.DEFAULT_BOLD);
                int cellPad = dp(host, 8);
                text.setPadding(cellPad, cellPad, cellPad, cellPad);
                text.setMinWidth(dp(host, 72));
                text.setMaxWidth(dp(host, 220));
                styler.apply(text);
                line.addView(
                        text,
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            grid.addView(
                    line,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        scroll.addView(
                grid,
                new HorizontalScrollView.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static List<Block> parse(String markdown, boolean streaming) {
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<Block> blocks = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        StringBuilder code = new StringBuilder();
        boolean inCode = false;
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                if (!inCode) {
                    flushText(blocks, text);
                    inCode = true;
                    code.setLength(0);
                } else {
                    blocks.add(Block.code(code.toString()));
                    code.setLength(0);
                    inCode = false;
                }
                i++;
                continue;
            }
            if (inCode) {
                if (code.length() > 0) code.append('\n');
                code.append(line);
                i++;
                continue;
            }
            if (isTableStart(lines, i)) {
                flushText(blocks, text);
                boolean header = i + 1 < lines.length && isSeparator(lines[i + 1]);
                List<String[]> rows = new ArrayList<>();
                while (i < lines.length && (isRow(lines[i]) || isSeparator(lines[i]))) {
                    if (!isSeparator(lines[i])) rows.add(cells(lines[i]));
                    i++;
                }
                if (!rows.isEmpty()) blocks.add(Block.table(rows, header));
                continue;
            }
            if (trimmed.equals("---") || trimmed.equals("***") || trimmed.equals("___")) {
                flushText(blocks, text);
                blocks.add(Block.rule());
                i++;
                continue;
            }
            if (text.length() > 0) text.append('\n');
            text.append(line);
            i++;
        }
        if (inCode) blocks.add(Block.code(code.toString()));
        else flushText(blocks, text);
        if (streaming) {
            if (!blocks.isEmpty() && "text".equals(blocks.get(blocks.size() - 1).kind)) {
                blocks.get(blocks.size() - 1).caret = true;
                blocks.get(blocks.size() - 1).sig += "|caret";
            } else {
                Block caret = Block.text(" ");
                caret.caret = true;
                caret.sig += "|caret";
                blocks.add(caret);
            }
        }
        return blocks;
    }

    private static void flushText(List<Block> blocks, StringBuilder text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\n') end--;
        if (end > 0) blocks.add(Block.text(text.substring(0, end)));
        text.setLength(0);
    }

    private static boolean isTableStart(String[] lines, int index) {
        if (!isRow(lines[index])) return false;
        if (index + 1 >= lines.length) return false;
        return isSeparator(lines[index + 1]) || isRow(lines[index + 1]);
    }

    private static boolean isRow(String line) {
        return cells(line).length >= 2 && line.indexOf('|') >= 0;
    }

    private static boolean isSeparator(String line) {
        String[] parts = cells(line);
        if (parts.length < 2 || line.indexOf('|') < 0) return false;
        for (String part : parts) {
            String cell = part.replace(":", "").replace("-", "").trim();
            if (!cell.isEmpty() || !part.contains("-")) return false;
        }
        return true;
    }

    private static String[] cells(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        String[] parts = trimmed.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
        return parts;
    }

    private static final class Block {
        final String kind;
        final String text;
        final List<String[]> rows;
        final boolean header;
        String sig;
        boolean caret;

        private Block(String kind, String text, List<String[]> rows, boolean header) {
            this.kind = kind;
            this.text = text;
            this.rows = rows;
            this.header = header;
            this.sig = kind + ":" + text.length() + ":" + rows.size() + ":" + text.hashCode();
        }

        static Block text(String text) {
            return new Block("text", text, List.of(), false);
        }

        static Block code(String text) {
            return new Block("code", text, List.of(), false);
        }

        static Block rule() {
            return new Block("rule", "", List.of(), false);
        }

        static Block table(List<String[]> rows, boolean header) {
            StringBuilder sig = new StringBuilder();
            for (String[] row : rows) {
                for (String cell : row) sig.append(cell).append('\u0001');
                sig.append('\n');
            }
            Block block = new Block("table", sig.toString(), rows, header);
            block.sig = "table:" + header + ":" + sig;
            return block;
        }
    }
}
