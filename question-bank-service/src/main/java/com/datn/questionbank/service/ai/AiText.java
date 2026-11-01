package com.datn.questionbank.service.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Models often answer maths in LaTeX ("$x^2 - \frac{1}{2}$") although the exam UI shows plain text. Common markup is
 * turned into readable Unicode (x² − 1/2); text without "$" or "\" is returned untouched, and anything unknown is
 * left as written rather than guessed.
 */
public final class AiText {
    private AiText() {}

    private static final Map<String, String> SYMBOLS = new LinkedHashMap<>();
    static {
        String[][] pairs = {{"\\leq", "≤"}, {"\\geq", "≥"}, {"\\le", "≤"}, {"\\ge", "≥"}, {"\\neq", "≠"}, {"\\ne", "≠"},
                {"\\times", "×"}, {"\\cdot", "·"}, {"\\div", "÷"}, {"\\pm", "±"}, {"\\infty", "∞"}, {"\\pi", "π"},
                {"\\alpha", "α"}, {"\\beta", "β"}, {"\\gamma", "γ"}, {"\\Delta", "Δ"}, {"\\delta", "δ"}, {"\\lambda", "λ"},
                {"\\omega", "ω"}, {"\\varphi", "φ"}, {"\\phi", "φ"}, {"\\theta", "θ"}, {"\\mu", "μ"}, {"\\sigma", "σ"},
                {"\\rightarrow", "→"}, {"\\to", "→"}, {"\\Rightarrow", "⇒"}, {"\\Leftrightarrow", "⇔"}, {"\\approx", "≈"},
                {"\\in", "∈"}, {"\\mathbb{R}", "ℝ"}, {"\\mathbb R", "ℝ"}, {"\\circ", "°"}, {"\\%", "%"}, {"\\,", " "}, {"\\;", " "},
                {"\\left", ""}, {"\\right", ""}, {"\\displaystyle", ""}};
        for (String[] p : pairs) SYMBOLS.put(p[0], p[1]);
    }
    private static final String SUP = "⁰¹²³⁴⁵⁶⁷⁸⁹", SUB = "₀₁₂₃₄₅₆₇₈₉";
    private static final Pattern FRAC = Pattern.compile("\\\\[dt]?frac\\{([^{}]*)\\}\\{([^{}]*)\\}");
    private static final Pattern SQRT = Pattern.compile("\\\\sqrt\\{([^{}]*)\\}");
    private static final Pattern SUPER = Pattern.compile("\\^\\{?(-?[0-9]+)\\}?");
    private static final Pattern SUBSCRIPT = Pattern.compile("_\\{?([0-9]+)\\}?");
    private static final Pattern TEXT = Pattern.compile("\\\\(?:text|mathrm|operatorname)\\{([^{}]*)\\}");

    public static String plain(String s) {
        if (s == null || (s.indexOf('$') < 0 && s.indexOf('\\') < 0)) return s;
        String t = s.replace("$$", "").replaceAll("(?<!\\\\)\\$", "").replace("\\(", "").replace("\\)", "").replace("\\[", "").replace("\\]", "");
        t = replaceAll(TEXT, t, m -> m.group(1));
        for (int i = 0; i < 3; i++) t = replaceAll(FRAC, t, m -> wrap(m.group(1)) + "/" + wrap(m.group(2)));
        t = replaceAll(SQRT, t, m -> "√" + wrap(m.group(1)));
        for (var e : SYMBOLS.entrySet()) t = t.replace(e.getKey(), e.getValue());
        t = replaceAll(SUPER, t, m -> digits(m.group(1), SUP));
        t = replaceAll(SUBSCRIPT, t, m -> digits(m.group(1), SUB));
        return t.replace("\\$", "$").replaceAll("[ \\t]{2,}", " ").strip();
    }

    private static String wrap(String x) { String v = x.strip(); return v.matches("[\\p{L}\\p{N}.,²³]+") ? v : "(" + v + ")"; }
    private static String digits(String n, String table) {
        var sb = new StringBuilder();
        for (char c : n.toCharArray()) sb.append(c == '-' ? (table == SUP ? '⁻' : '₋') : table.charAt(c - '0'));
        return sb.toString();
    }
    private static String replaceAll(Pattern p, String s, java.util.function.Function<Matcher, String> f) {
        Matcher m = p.matcher(s);
        var sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(f.apply(m)));
        m.appendTail(sb);
        return sb.toString();
    }
}
