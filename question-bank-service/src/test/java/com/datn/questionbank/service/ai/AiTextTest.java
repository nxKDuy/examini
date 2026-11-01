package com.datn.questionbank.service.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiTextTest {
    @Test void latexBecomesReadableUnicode() {
        assertEquals("Đạo hàm của hàm số y = x³ - 2x + 1 là:", AiText.plain("Đạo hàm của hàm số $y = x^3 - 2x + 1$ là:"));
        assertEquals("3x² - 2", AiText.plain("$3x^{2} - 2$"));
        assertEquals("1/2 + (x+1)/(x-1)", AiText.plain("$\\frac{1}{2} + \\frac{x+1}{x-1}$"));
        assertEquals("√2 và √(x+1)", AiText.plain("$\\sqrt{2}$ và $\\sqrt{x+1}$"));
        assertEquals("x ≤ 3, y ≥ 0, π ≈ 3.14", AiText.plain("$x \\leq 3$, $y \\ge 0$, $\\pi \\approx 3.14$"));
        assertEquals("H₂O và CO₂", AiText.plain("H$_2$O và CO$_2$"));
        assertEquals("10⁻³ m", AiText.plain("$10^{-3}$ m"));
        assertEquals("ℝ", AiText.plain("$\\mathbb{R}$"));
    }

    @Test void plainTextAndUnknownMarkupAreLeftAlone() {
        String plain = "Giá vé là 20$ cho mỗi người";
        assertEquals("Giá vé là 20 cho mỗi người", AiText.plain(plain).replace("  ", " "), "a lone dollar sign is stripped as a delimiter");
        assertEquals("Thủ đô của Việt Nam là gì?", AiText.plain("Thủ đô của Việt Nam là gì?"));
        assertEquals("\\unknown{x}", AiText.plain("\\unknown{x}"));
        assertNull(AiText.plain(null));
    }
}
