// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.lemurx.muse;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Base64;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把模型交回来的 Markdown 排成真正的文件。PDF 是 A4 矢量页，Word 是标准 docx。
 * 聊天里的预览用 PDF 的前几页；点打开时交给系统里的阅读器。
 */
@NullMarked
final class LemurXDocument {
    private static final int PAGE_W = 595;
    private static final int PAGE_H = 842;
    private static final int MARGIN = 50;
    private static final int TOP = 46;
    private static final int BOTTOM = 800;
    private static final int CONTENT_W = PAGE_W - MARGIN * 2;
    private static final int INK = 0xFF132631;
    private static final int MUTED = 0xFF6B7680;
    private static final int LINE = 0xFFE6E8EA;
    private static final int PAPER = 0xFFF7F8F8;
    private static final int MAX_PAGES = 80;

    private static final int H1 = 1;
    private static final int H2 = 2;
    private static final int H3 = 3;
    private static final int PARA = 4;
    private static final int BULLET = 5;
    private static final int NUMBER = 6;
    private static final int QUOTE = 7;
    private static final int CODE = 8;
    private static final int RULE = 9;
    private static final int TABLE = 10;

    private static final class Block {
        final int type;
        final String text;
        final int index;
        final @Nullable String[][] table;

        Block(int type, String text, int index, @Nullable String[][] table) {
            this.type = type;
            this.text = text;
            this.index = index;
            this.table = table;
        }
    }

    private LemurXDocument() {}

    /** A4 PDF。正文是 Markdown。 */
    static byte[] pdf(String name, String markdown) throws IOException {
        List<Block> blocks = parse(markdown);
        String running = runningTitle(name, blocks);
        PdfDocument doc = new PdfDocument();
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(INK);
        paint.setTypeface(Typeface.SANS_SERIF);
        Session session = new Session(doc, paint, running);
        open(session);
        for (int i = 0; i < blocks.size(); i++) draw(session, blocks.get(i));
        close(session);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.writeTo(out);
        doc.close();
        return out.toByteArray();
    }

    /** 标准 Word。同一份 Markdown，中文用宋体，西文用 Calibri。 */
    static byte[] docx(String name, String markdown) throws IOException {
        List<Block> blocks = parse(markdown);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(raw);
        put(zip, "[Content_Types].xml", contentTypes());
        put(zip, "_rels/.rels", rootRels());
        put(zip, "word/_rels/document.xml.rels", documentRels());
        put(zip, "word/styles.xml", styles());
        put(zip, "word/numbering.xml", numbering());
        put(zip, "word/footer1.xml", footer(runningTitle(name, blocks)));
        put(zip, "word/document.xml", documentXml(blocks));
        zip.close();
        return raw.toByteArray();
    }

    /** 前几页画成 JPEG，给聊天里的预览用。文件本身不受这个张数限制。 */
    static List<String> pageImages(byte[] pdf, int limit) throws IOException {
        List<String> pages = new ArrayList<>();
        if (limit <= 0 || pdf.length == 0) return pages;
        File tmp = File.createTempFile("aero-doc-", ".pdf");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(pdf);
            }
            ParcelFileDescriptor fd =
                    ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY);
            // PdfRenderer 会关掉这个 fd，所以不要再关一次。
            try (PdfRenderer renderer = new PdfRenderer(fd)) {
                int count = Math.min(renderer.getPageCount(), limit);
                for (int i = 0; i < count; i++) {
                    PdfRenderer.Page page = renderer.openPage(i);
                    try {
                        BitmapPage bitmap = BitmapPage.render(page);
                        if (bitmap.jpeg != null) pages.add(bitmap.jpeg);
                    } finally {
                        page.close();
                    }
                }
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        return pages;
    }

    private static final class BitmapPage {
        final @Nullable String jpeg;

        private BitmapPage(@Nullable String jpeg) {
            this.jpeg = jpeg;
        }

        static BitmapPage render(PdfRenderer.Page page) {
            int w = Math.max(1, page.getWidth() * 2);
            int h = Math.max(1, page.getHeight() * 2);
            android.graphics.Bitmap bitmap =
                    android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, out);
                if (out.size() == 0) return new BitmapPage(null);
                return new BitmapPage(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
            } finally {
                bitmap.recycle();
            }
        }
    }

    private static final class Session {
        final PdfDocument doc;
        final TextPaint paint;
        final String running;
        PdfDocument.@Nullable Page page;
        @Nullable Canvas canvas;
        int index;
        int y;

        Session(PdfDocument doc, TextPaint paint, String running) {
            this.doc = doc;
            this.paint = paint;
            this.running = running;
        }
    }

    private static void open(Session session) {
        if (session.index >= MAX_PAGES) return;
        close(session);
        session.index++;
        session.page =
                session.doc.startPage(
                        new PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, session.index).create());
        session.canvas = session.page.getCanvas();
        session.canvas.drawColor(Color.WHITE);
        session.y = TOP;
    }

    private static void close(Session session) {
        PdfDocument.Page page = session.page;
        Canvas canvas = session.canvas;
        if (page == null || canvas == null) return;
        session.paint.setColor(MUTED);
        session.paint.setTextSize(9f);
        session.paint.setTypeface(Typeface.SANS_SERIF);
        String title = session.running;
        float max = CONTENT_W - 36;
        while (title.length() > 1 && session.paint.measureText(title) > max) {
            title = title.substring(0, title.length() - 1);
        }
        canvas.drawText(title, MARGIN, PAGE_H - 28, session.paint);
        String number = Integer.toString(session.index);
        float nw = session.paint.measureText(number);
        canvas.drawText(number, PAGE_W - MARGIN - nw, PAGE_H - 28, session.paint);
        session.paint.setColor(INK);
        session.doc.finishPage(page);
        session.page = null;
        session.canvas = null;
    }

    private static void draw(Session session, Block block) {
        if (session.page == null) return;
        if (block.type == RULE) {
            ensure(session, 18);
            Canvas canvas = session.canvas;
            if (canvas == null) return;
            session.paint.setColor(LINE);
            session.paint.setStrokeWidth(1f);
            canvas.drawLine(MARGIN, session.y + 8, MARGIN + CONTENT_W, session.y + 8, session.paint);
            session.paint.setColor(INK);
            session.y += 18;
            return;
        }
        if (block.type == TABLE) {
            String[][] table = block.table;
            if (table != null) drawTable(session, table);
            return;
        }
        float size = block.type == H1 ? 20f : block.type == H2 ? 15f : block.type == H3 ? 13f : block.type == CODE ? 9.5f : 11f;
        int gap = block.type == H1 ? 12 : block.type == H2 || block.type == H3 ? 8 : block.type == CODE ? 12 : block.type == BULLET || block.type == NUMBER ? 3 : 10;
        session.paint.setTextSize(size);
        session.paint.setTypeface(
                block.type == CODE
                        ? Typeface.MONOSPACE
                        : block.type == H1 || block.type == H2 || block.type == H3
                                ? Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                                : Typeface.SANS_SERIF);
        session.paint.setColor(block.type == QUOTE ? MUTED : INK);
        CharSequence text =
                block.type == CODE
                        ? block.text
                        : MuseMarkdown.render(sourceLine(block), INK, null);
        int width = block.type == QUOTE || block.type == CODE ? CONTENT_W - 14 : CONTENT_W;
        StaticLayout layout = layout(text, session.paint, width);
        int pad = block.type == QUOTE || block.type == CODE ? 14 : 0;
        int bg = block.type == CODE ? PAPER : 0;
        drawLayout(session, layout, gap, pad, bg, block.type == QUOTE);
        session.paint.setColor(INK);
    }

    private static String sourceLine(Block block) {
        if (block.type == BULLET) return "- " + block.text;
        if (block.type == NUMBER) return block.index + ". " + block.text;
        if (block.type == QUOTE) return "> " + block.text;
        return block.text;
    }

    private static void drawLayout(
            Session session, StaticLayout layout, int gap, int pad, int background, boolean quote) {
        int line = 0;
        int count = layout.getLineCount();
        while (line < count && session.index < MAX_PAGES) {
            if (BOTTOM - session.y < 16) open(session);
            Canvas canvas = session.canvas;
            if (canvas == null) return;
            int room = Math.max(16, BOTTOM - session.y);
            int top = layout.getLineTop(line);
            int next = line;
            while (next < count && layout.getLineBottom(next) - top <= room) next++;
            if (next == line) next = line + 1;
            int bottom = layout.getLineBottom(next - 1);
            int height = bottom - top;
            if (background != 0) {
                session.paint.setColor(background);
                canvas.drawRect(MARGIN, session.y - 2, MARGIN + CONTENT_W, session.y + height + 2, session.paint);
                session.paint.setColor(INK);
            }
            if (quote) {
                session.paint.setColor(LINE);
                canvas.drawRect(MARGIN, session.y, MARGIN + 3, session.y + height, session.paint);
                session.paint.setColor(MUTED);
            }
            canvas.save();
            canvas.translate(MARGIN + pad, session.y - top);
            canvas.clipRect(-pad, top, CONTENT_W - pad, bottom + 1);
            layout.draw(canvas);
            canvas.restore();
            session.y += height;
            line = next;
            if (line < count) open(session);
        }
        session.y += gap;
    }

    private static void drawTable(Session session, String[][] rows) {
        int cols = 0;
        for (String[] row : rows) cols = Math.max(cols, row.length);
        if (cols == 0) return;
        int[] widths = new int[cols];
        int share = Math.max(1, CONTENT_W / cols);
        for (int c = 0; c < cols; c++) widths[c] = share;
        session.paint.setTextSize(9.5f);
        session.paint.setTypeface(Typeface.SANS_SERIF);
        for (int r = 0; r < rows.length && session.index < MAX_PAGES; r++) {
            StaticLayout[] cells = new StaticLayout[cols];
            int height = 0;
            for (int c = 0; c < cols; c++) {
                String value = c < rows[r].length ? rows[r][c] : "";
                session.paint.setTypeface(
                        r == 0
                                ? Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                                : Typeface.SANS_SERIF);
                cells[c] = layout(MuseMarkdown.render(value, INK, null), session.paint, widths[c] - 10);
                height = Math.max(height, cells[c].getHeight() + 10);
            }
            if (session.y + height > BOTTOM) open(session);
            Canvas canvas = session.canvas;
            if (canvas == null) return;
            int x = MARGIN;
            for (int c = 0; c < cols; c++) {
                if (r == 0) {
                    session.paint.setColor(PAPER);
                    canvas.drawRect(x, session.y, x + widths[c], session.y + height, session.paint);
                }
                session.paint.setColor(LINE);
                session.paint.setStyle(Paint.Style.STROKE);
                canvas.drawRect(x, session.y, x + widths[c], session.y + height, session.paint);
                session.paint.setStyle(Paint.Style.FILL);
                session.paint.setColor(INK);
                canvas.save();
                canvas.translate(x + 5, session.y + 5);
                cells[c].draw(canvas);
                canvas.restore();
                x += widths[c];
            }
            session.y += height;
        }
        session.y += 12;
    }

    private static void ensure(Session session, int need) {
        if (BOTTOM - session.y < need) open(session);
    }

    private static StaticLayout layout(CharSequence text, TextPaint paint, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.max(1, width))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.28f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .build();
    }

    private static String runningTitle(String name, List<Block> blocks) {
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (block.type == H1 && !block.text.isEmpty()) return block.text;
        }
        int dot = name.lastIndexOf('.');
        String bare = dot > 0 ? name.substring(0, dot) : name;
        return bare.isEmpty() ? "Aero" : bare;
    }

    private static List<Block> parse(String markdown) {
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<Block> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        int number = 0;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("```")) {
                flush(blocks, paragraph);
                number = 0;
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    if (code.length() > 0) code.append('\n');
                    code.append(lines[i]);
                    i++;
                }
                blocks.add(new Block(CODE, code.toString(), 0, null));
                continue;
            }
            if (trimmed.isEmpty()) {
                flush(blocks, paragraph);
                number = 0;
                continue;
            }
            if (isTableStart(lines, i)) {
                flush(blocks, paragraph);
                number = 0;
                List<String[]> rows = new ArrayList<>();
                rows.add(cells(trimmed));
                i += 2;
                while (i < lines.length && lines[i].contains("|") && !lines[i].trim().isEmpty()) {
                    rows.add(cells(lines[i].trim()));
                    i++;
                }
                i--;
                int cols = 0;
                for (String[] row : rows) cols = Math.max(cols, row.length);
                String[][] table = new String[rows.size()][cols];
                for (int r = 0; r < rows.size(); r++) {
                    String[] row = rows.get(r);
                    System.arraycopy(row, 0, table[r], 0, row.length);
                    for (int c = row.length; c < cols; c++) table[r][c] = "";
                }
                blocks.add(new Block(TABLE, "", 0, table));
                continue;
            }
            int level = headingLevel(trimmed);
            if (level > 0) {
                flush(blocks, paragraph);
                number = 0;
                int type = level == 1 ? H1 : level == 2 ? H2 : H3;
                blocks.add(new Block(type, trimmed.substring(level).trim(), 0, null));
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ")) {
                flush(blocks, paragraph);
                number = 0;
                blocks.add(new Block(BULLET, trimmed.substring(2).trim(), 0, null));
                continue;
            }
            if (isOrdered(trimmed)) {
                flush(blocks, paragraph);
                int dot = trimmed.indexOf('.');
                number++;
                blocks.add(new Block(NUMBER, trimmed.substring(dot + 1).trim(), number, null));
                continue;
            }
            if (trimmed.startsWith("> ")) {
                flush(blocks, paragraph);
                number = 0;
                blocks.add(new Block(QUOTE, trimmed.substring(2).trim(), 0, null));
                continue;
            }
            if (trimmed.equals("---") || trimmed.equals("***") || trimmed.equals("___")) {
                flush(blocks, paragraph);
                number = 0;
                blocks.add(new Block(RULE, "", 0, null));
                continue;
            }
            if (paragraph.length() > 0) paragraph.append(join(paragraph, trimmed));
            else paragraph.append(trimmed);
        }
        flush(blocks, paragraph);
        if (blocks.isEmpty()) blocks.add(new Block(PARA, "", 0, null));
        return blocks;
    }

    private static void flush(List<Block> blocks, StringBuilder paragraph) {
        if (paragraph.length() == 0) return;
        blocks.add(new Block(PARA, paragraph.toString(), 0, null));
        paragraph.setLength(0);
    }

    private static String join(StringBuilder previous, String next) {
        char end = previous.charAt(previous.length() - 1);
        char start = next.isEmpty() ? ' ' : next.charAt(0);
        if (isCjk(end) || isCjk(start)) return next;
        return " " + next;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    /** 井号个数。后面必须有空格才算标题，正文用 substring(level) 再 trim。 */
    private static int headingLevel(String trimmed) {
        int level = 0;
        while (level < trimmed.length() && level < 3 && trimmed.charAt(level) == '#') level++;
        if (level > 0 && level < trimmed.length() && trimmed.charAt(level) == ' ') return level;
        return 0;
    }

    private static boolean isOrdered(String trimmed) {
        int i = 0;
        while (i < trimmed.length() && i < 3 && Character.isDigit(trimmed.charAt(i))) i++;
        return i > 0 && i + 1 < trimmed.length() && trimmed.charAt(i) == '.' && trimmed.charAt(i + 1) == ' ';
    }

    private static boolean isTableStart(String[] lines, int index) {
        if (!lines[index].contains("|") || index + 1 >= lines.length) return false;
        String rule = lines[index + 1].trim();
        if (!rule.contains("-") || !rule.contains("|")) return false;
        for (int i = 0; i < rule.length(); i++) {
            char c = rule.charAt(i);
            if (c != '|' && c != '-' && c != ':' && c != ' ') return false;
        }
        return true;
    }

    private static String[] cells(String line) {
        String raw = line;
        if (raw.startsWith("|")) raw = raw.substring(1);
        if (raw.endsWith("|")) raw = raw.substring(0, raw.length() - 1);
        String[] parts = raw.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
        return parts;
    }

    private static void put(ZipOutputStream zip, String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String contentTypes() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>"
                + "<Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/>"
                + "</Types>";
    }

    private static String rootRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>";
    }

    private static String documentRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer\" Target=\"footer1.xml\"/>"
                + "</Relationships>";
    }

    private static String styles() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + style("Normal", "Normal", false, 22, 0, "000000", false)
                + style("Heading1", "heading 1", true, 40, 240, "132631", false)
                + style("Heading2", "heading 2", true, 32, 200, "132631", false)
                + style("Heading3", "heading 3", true, 26, 160, "132631", false)
                + style("Quote", "Quote", false, 22, 120, "6B7680", true)
                + "</w:styles>";
    }

    private static String style(
            String id, String name, boolean bold, int halfPoints, int after, String color, boolean italic) {
        return "<w:style w:type=\"paragraph\" w:styleId=\""
                + id
                + "\">"
                + "<w:name w:val=\""
                + name
                + "\"/>"
                + "<w:qFormat/>"
                + "<w:pPr><w:spacing w:after=\""
                + after
                + "\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr>"
                + "<w:rPr>"
                + "<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"宋体\" w:cs=\"Calibri\"/>"
                + (bold ? "<w:b/>" : "")
                + (italic ? "<w:i/>" : "")
                + "<w:color w:val=\""
                + color
                + "\"/>"
                + "<w:sz w:val=\""
                + halfPoints
                + "\"/><w:szCs w:val=\""
                + halfPoints
                + "\"/>"
                + "<w:lang w:val=\"en-US\" w:eastAsia=\"zh-CN\"/>"
                + "</w:rPr></w:style>";
    }

    private static String numbering() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"hybridMultilevel\"/>"
                + "<w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"•\"/>"
                + "<w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"420\" w:hanging=\"420\"/></w:pPr></w:lvl></w:abstractNum>"
                + "<w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"hybridMultilevel\"/>"
                + "<w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/>"
                + "<w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"420\" w:hanging=\"420\"/></w:pPr></w:lvl></w:abstractNum>"
                + "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>"
                + "<w:num w:numId=\"2\"><w:abstractNumId w:val=\"1\"/></w:num>"
                + "</w:numbering>";
    }

    private static String footer(String title) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:ftr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:p><w:pPr><w:tabs><w:tab w:val=\"right\" w:pos=\"9360\"/></w:tabs></w:pPr>"
                + "<w:r><w:rPr><w:color w:val=\"6B7680\"/><w:sz w:val=\"18\"/>"
                + "<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"宋体\"/></w:rPr>"
                + "<w:t xml:space=\"preserve\">"
                + xml(title)
                + "</w:t></w:r>"
                + "<w:r><w:tab/></w:r>"
                + "<w:r><w:rPr><w:color w:val=\"6B7680\"/><w:sz w:val=\"18\"/></w:rPr>"
                + "<w:fldChar w:fldCharType=\"begin\"/></w:r>"
                + "<w:r><w:rPr><w:color w:val=\"6B7680\"/><w:sz w:val=\"18\"/></w:rPr>"
                + "<w:instrText xml:space=\"preserve\"> PAGE </w:instrText></w:r>"
                + "<w:r><w:rPr><w:color w:val=\"6B7680\"/><w:sz w:val=\"18\"/></w:rPr>"
                + "<w:fldChar w:fldCharType=\"end\"/></w:r></w:p></w:ftr>";
    }

    private static String documentXml(List<Block> blocks) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) body.append(blockXml(blocks.get(i)));
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<w:body>"
                + body
                + "<w:sectPr>"
                + "<w:footerReference w:type=\"default\" r:id=\"rId3\"/>"
                + "<w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                + "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"720\" w:footer=\"720\"/>"
                + "</w:sectPr></w:body></w:document>";
    }

    private static String blockXml(Block block) {
        if (block.type == RULE) return "<w:p/>";
        if (block.type == TABLE) return tableXml(block.table);
        if (block.type == CODE) {
            String[] lines = block.text.split("\n", -1);
            StringBuilder out = new StringBuilder();
            for (String line : lines) {
                out.append("<w:p><w:pPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F4F5F6\"/></w:pPr>");
                out.append("<w:r><w:rPr><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:eastAsia=\"宋体\"/>");
                out.append("<w:sz w:val=\"18\"/><w:szCs w:val=\"18\"/></w:rPr><w:t xml:space=\"preserve\">");
                out.append(xml(line)).append("</w:t></w:r></w:p>");
            }
            return out.toString();
        }
        String style =
                block.type == H1
                        ? "Heading1"
                        : block.type == H2
                                ? "Heading2"
                                : block.type == H3 ? "Heading3" : block.type == QUOTE ? "Quote" : "Normal";
        StringBuilder p = new StringBuilder();
        p.append("<w:p><w:pPr><w:pStyle w:val=\"").append(style).append("\"/>");
        if (block.type == BULLET) {
            p.append("<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>");
        } else if (block.type == NUMBER) {
            p.append("<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"2\"/></w:numPr>");
        }
        p.append("</w:pPr>");
        runs(p, flattenLinks(block.text));
        p.append("</w:p>");
        return p.toString();
    }

    private static String tableXml(@Nullable String[][] table) {
        if (table == null || table.length == 0) return "";
        StringBuilder out = new StringBuilder();
        out.append("<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/>");
        out.append("<w:tblBorders>");
        for (String edge : new String[] {"top", "left", "bottom", "right", "insideH", "insideV"}) {
            out.append("<w:")
                    .append(edge)
                    .append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"E6E8EA\"/>");
        }
        out.append("</w:tblBorders></w:tblPr>");
        for (int r = 0; r < table.length; r++) {
            out.append("<w:tr>");
            for (int c = 0; c < table[r].length; c++) {
                out.append("<w:tc><w:tcPr>");
                if (r == 0) out.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F4F5F6\"/>");
                out.append("</w:tcPr><w:p>");
                runs(out, flattenLinks(table[r][c]));
                out.append("</w:p></w:tc>");
            }
            out.append("</w:tr>");
        }
        out.append("</w:tbl>");
        return out.toString();
    }

    private static void runs(StringBuilder xml, String text) {
        int i = 0;
        while (i < text.length()) {
            if (starts(text, i, "**")) {
                int end = text.indexOf("**", i + 2);
                if (end > i + 2) {
                    run(xml, text.substring(i + 2, end), true, false, false);
                    i = end + 2;
                    continue;
                }
            }
            if (starts(text, i, "`")) {
                int end = text.indexOf('`', i + 1);
                if (end > i + 1) {
                    run(xml, text.substring(i + 1, end), false, false, true);
                    i = end + 1;
                    continue;
                }
            }
            if (starts(text, i, "*") && (i + 1 >= text.length() || text.charAt(i + 1) != '*')) {
                int end = text.indexOf('*', i + 1);
                if (end > i + 1) {
                    run(xml, text.substring(i + 1, end), false, true, false);
                    i = end + 1;
                    continue;
                }
            }
            int next = text.length();
            int bold = text.indexOf("**", i);
            int tick = text.indexOf('`', i);
            int star = text.indexOf('*', i);
            if (bold >= i && bold < next) next = bold;
            if (tick >= i && tick < next) next = tick;
            if (star >= i && star < next) next = star;
            if (next == i) next = i + 1;
            run(xml, text.substring(i, next), false, false, false);
            i = next;
        }
    }

    private static boolean starts(String text, int index, String token) {
        return text.startsWith(token, index);
    }

    private static void run(StringBuilder xml, String text, boolean bold, boolean italic, boolean code) {
        if (text.isEmpty()) return;
        xml.append("<w:r><w:rPr>");
        if (code) {
            xml.append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:eastAsia=\"宋体\"/>");
            xml.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F4F5F6\"/>");
        }
        if (bold) xml.append("<w:b/>");
        if (italic) xml.append("<w:i/>");
        xml.append("</w:rPr><w:t xml:space=\"preserve\">").append(xml(text)).append("</w:t></w:r>");
    }

    private static String flattenLinks(String text) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf('[', i);
            if (open < 0) {
                out.append(text.substring(i));
                break;
            }
            int mid = text.indexOf("](", open + 1);
            int end = mid < 0 ? -1 : text.indexOf(')', mid + 2);
            if (mid < 0 || end < 0) {
                out.append(text.substring(i));
                break;
            }
            out.append(text, i, open);
            out.append(text, open + 1, mid);
            out.append(' ');
            out.append(text, mid + 2, end);
            i = end + 1;
        }
        return out.toString();
    }

    private static String xml(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&') out.append("&amp;");
            else if (c == '<') out.append("&lt;");
            else if (c == '>') out.append("&gt;");
            else if (c == '"') out.append("&quot;");
            else out.append(c);
        }
        return out.toString();
    }
}
