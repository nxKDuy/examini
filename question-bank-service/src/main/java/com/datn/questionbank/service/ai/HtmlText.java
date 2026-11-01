package com.datn.questionbank.service.ai;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Readable text of an HTML page without a parser dependency: drops scripts, styles, navigation, headers, footers,
 * forms and asides; prefers the {@code <article>} or {@code <main>} region when the page has one; keeps paragraph,
 * heading, list and table-cell breaks; decodes entities. Good enough for lesson pages, Wikipedia and news articles.
 */
final class HtmlText {
    private HtmlText() {}

    private static final Pattern DROP = Pattern.compile(
            "(?is)<(script|style|noscript|template|svg|iframe|nav|header|footer|aside|form|button|select|math)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern BLOCK = Pattern.compile(
            "(?i)</?(p|div|section|article|main|h[1-6]|li|ul|ol|tr|table|blockquote|pre|figure|figcaption|dd|dt|dl)\\b[^>]*>|<br\\s*/?>");
    private static final Pattern CELL = Pattern.compile("(?i)<(td|th)\\b[^>]*>");
    private static final Pattern LI = Pattern.compile("(?i)<li\\b[^>]*>");
    private static final Pattern MATH_IMG = Pattern.compile("(?i)<img\\b[^>]*\\balt\\s*=\\s*\"\\{\\\\(?:displaystyle|textstyle)\\s*([^\"]*?)\\s*\\}\"[^>]*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);");
    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("quot", "\""), Map.entry("apos", "'"),
            Map.entry("nbsp", " "), Map.entry("ndash", "–"), Map.entry("mdash", "—"), Map.entry("hellip", "…"), Map.entry("laquo", "«"),
            Map.entry("raquo", "»"), Map.entry("ldquo", "“"), Map.entry("rdquo", "”"), Map.entry("lsquo", "‘"), Map.entry("rsquo", "’"),
            Map.entry("deg", "°"), Map.entry("times", "×"), Map.entry("divide", "÷"), Map.entry("plusmn", "±"), Map.entry("le", "≤"),
            Map.entry("ge", "≥"), Map.entry("ne", "≠"), Map.entry("asymp", "≈"), Map.entry("pi", "π"), Map.entry("copy", "©"), Map.entry("middot", "·"));

    static String text(String html) {
        if (html == null) return "";
        String s = COMMENT.matcher(html).replaceAll(" ");
        s = DROP.matcher(s).replaceAll(" ");
        s = dropBoilerplate(s);
        // Formula images (MediaWiki) carry their TeX in alt: keep it once, as readable text.
        s = MATH_IMG.matcher(s).replaceAll(r -> " " + Matcher.quoteReplacement(r.group(1).strip()) + " ");
        String region = region(s, "article");
        if (region == null) region = region(s, "main");
        if (region != null && TAG.matcher(region).replaceAll("").strip().length() > 400) s = region;
        else {
            int body = indexOfIgnoreCase(s, "<body");
            if (body >= 0) s = s.substring(body);
        }
        s = LI.matcher(s).replaceAll("\n• ");
        s = CELL.matcher(s).replaceAll(" | ");
        s = BLOCK.matcher(s).replaceAll("\n");
        s = TAG.matcher(s).replaceAll("");
        s = unescape(s);
        var out = new StringBuilder();
        for (String line : s.split("\n")) {
            String l = line.replace('\u00a0', ' ').replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("^( ?\\| ?)+|( ?\\| ?)+$", "").strip();
            if (l.isEmpty() || l.equals("•")) { if (out.length() > 0 && !out.toString().endsWith("\n\n")) out.append('\n'); continue; }
            out.append(l).append('\n');
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    /**
     * Site chrome that lives inside the content region: maintenance banners, info/navigation boxes, tables of contents,
     * edit links, footnote markers and reference lists (Wikipedia/MediaWiki and common CMS class names). Removal is
     * nesting-aware, so a banner made of nested divs or spans goes as a whole.
     */
    private static final Pattern CHROME = Pattern.compile(
            "(?i)<(div|table|span|sup|ol|ul|section|figure)\\b[^>]*\\b(?:class|id|role)\\s*=\\s*\"(?:[^\"]*\\s)?(ambox|navbox|vertical-navbox|infobox|metadata|sidebar|hatnote|"
            + "toc|mw-editsection|reference|references|reflist|mw-references-wrap|catlinks|noprint|siteSub|contentSub|jump-to-nav|mw-jump-link|printfooter|"
            + "share|social|breadcrumb|cookie|advert|ads|related|comments?|newsletter|navigation)(?:\\s[^\"]*)?\"[^>]*>");
    static String dropBoilerplate(String html) {
        var sb = new StringBuilder(html);
        Matcher m = CHROME.matcher(sb);
        int from = 0;
        while (m.find(from)) {
            String tag = m.group(1).toLowerCase();
            int end = closing(sb, tag, m.end());
            if (end < 0) { from = m.end(); m = CHROME.matcher(sb); continue; }
            sb.replace(m.start(), end, " ");
            from = m.start();
            m = CHROME.matcher(sb);
        }
        return sb.toString();
    }
    /** Index just past the tag that closes the element opened before {@code from}, counting nested same-name tags. */
    private static int closing(CharSequence s, String tag, int from) {
        Matcher t = Pattern.compile("(?i)<(/?)" + tag + "\\b[^>]*?(/?)>").matcher(s);
        int depth = 1;
        while (t.find(from)) {
            from = t.end();
            if (!t.group(2).isEmpty()) continue;           // self-closing
            depth += t.group(1).isEmpty() ? 1 : -1;
            if (depth == 0) return t.end();
        }
        return -1;
    }

    /** Plain text as served: trims each line and collapses runs of blank lines. */
    static String plain(String text) {
        var out = new StringBuilder();
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String l = line.replace('\u00a0', ' ').strip();
            if (l.isEmpty()) { if (out.length() > 0 && !out.toString().endsWith("\n\n")) out.append('\n'); continue; }
            out.append(l).append('\n');
        }
        return out.toString().strip();
    }

    /** Inner HTML of the longest {@code <tag>…</tag>} region, or null. */
    private static String region(String html, String tag) {
        Matcher m = Pattern.compile("(?is)<" + tag + "\\b[^>]*>(.*?)</" + tag + "\\s*>").matcher(html);
        String best = null;
        while (m.find()) if (best == null || m.group(1).length() > best.length()) best = m.group(1);
        return best;
    }

    static String unescape(String s) {
        Matcher m = ENTITY.matcher(s);
        var sb = new StringBuilder();
        while (m.find()) {
            String e = m.group(1), r;
            try {
                if (e.startsWith("#x") || e.startsWith("#X")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                else r = NAMED.getOrDefault(e.toLowerCase(), m.group());
            } catch (RuntimeException ex) { r = m.group(); }
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static int indexOfIgnoreCase(String s, String needle) { return s.toLowerCase().indexOf(needle); }
}
