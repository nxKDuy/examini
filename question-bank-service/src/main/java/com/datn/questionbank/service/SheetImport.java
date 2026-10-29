package com.datn.questionbank.service;

import com.datn.questionbank.entity.Question.DifficultyLevel;
import com.datn.questionbank.entity.Question.QuestionType;
import com.datn.questionbank.entity.QuestionImportItem.SourceMethod;
import com.opencsv.CSVReaderBuilder;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/**
 * Spreadsheet import (Excel .xlsx or CSV) from the downloadable template: one question per row, columns found by
 * their header (Vietnamese or English, case and accents ignored). Rows are staged for review like documents; a row
 * the parser is unsure about is flagged instead of guessed.
 */
public final class SheetImport {
    private SheetImport() {}

    /** Header aliases → canonical column. */
    private static final Map<String, String> HEADERS = new LinkedHashMap<>();
    static {
        for (var h : List.of("cau hoi", "noi dung", "question", "content")) HEADERS.put(h, "content");
        for (var h : List.of("loai", "loai cau", "loai cau hoi", "type", "question type")) HEADERS.put(h, "type");
        for (var h : List.of("do kho", "muc do", "difficulty", "level")) HEADERS.put(h, "difficulty");
        for (var h : List.of("dap an", "dap an dung", "answer", "correct")) HEADERS.put(h, "answer");
        for (var h : List.of("giai thich", "loi giai", "explanation")) HEADERS.put(h, "explanation");
        for (var l : "ABCDEFGH".toCharArray()) { HEADERS.put(String.valueOf(l).toLowerCase(Locale.ROOT), "opt" + l); HEADERS.put("phuong an " + Character.toLowerCase(l), "opt" + l); HEADERS.put("option " + Character.toLowerCase(l), "opt" + l); }
    }

    static String key(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D');
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").strip();
    }

    static QuestionType type(String raw, boolean hasOptions) {
        String k = key(raw);
        if (k.isEmpty()) return null;
        if (k.matches("trac nghiem|tn|mcq|multiple choice|nhieu lua chon|chon dap an")) return QuestionType.MULTIPLE_CHOICE;
        if (k.matches("dung sai|d s|ds|true false|tf|dung/sai")) return QuestionType.TRUE_FALSE;
        if (k.matches("tra loi ngan|dien|dien khuyet|short answer|short|tln")) return QuestionType.SHORT_ANSWER;
        if (k.matches("tu luan|viet|essay|writing|tl")) return QuestionType.ESSAY;
        if (k.matches("noi|speaking|ghi am|van dap")) return QuestionType.SPEAKING;
        try { return QuestionType.valueOf(raw.strip().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
    }

    static DifficultyLevel difficulty(String raw, DifficultyLevel fallback) {
        String k = key(raw);
        if (k.isEmpty()) return fallback;
        if (k.matches("de|nhan biet|nb|easy|1")) return DifficultyLevel.EASY;
        if (k.matches("trung binh|tb|thong hieu|th|medium|2")) return DifficultyLevel.MEDIUM;
        if (k.matches("kho|van dung|vd|van dung cao|vdc|hard|3")) return DifficultyLevel.HARD;
        return null;
    }

    public static List<ImportParser.Parsed> parse(byte[] bytes, boolean csv, DifficultyLevel defaultDifficulty) {
        List<List<String>> rows = csv ? csvRows(bytes) : xlsxRows(bytes);
        int headerRow = -1;
        Map<String, Integer> cols = new HashMap<>();
        for (int r = 0; r < Math.min(rows.size(), 15) && headerRow < 0; r++) {
            var found = new HashMap<String, Integer>();
            var row = rows.get(r);
            for (int c = 0; c < row.size(); c++) { String canon = HEADERS.get(key(row.get(c))); if (canon != null) found.putIfAbsent(canon, c); }
            if (found.containsKey("content")) { headerRow = r; cols = found; }
        }
        if (headerRow < 0) throw new DocumentExtractor.ExtractionException("Không thấy cột \"Câu hỏi\" ở dòng tiêu đề. Hãy dùng file mẫu.");
        var out = new ArrayList<ImportParser.Parsed>();
        for (int r = headerRow + 1; r < rows.size() && out.size() < ImportParser.MAX_ITEMS; r++) {
            var row = rows.get(r);
            String content = cell(row, cols.get("content"));
            if (content.isBlank()) continue;
            if (key(content).startsWith("vi du") && r == headerRow + 1 && content.length() < 12) continue;
            var flags = EnumSet.noneOf(ImportParser.Flag.class);
            double confidence = 1.0;
            var options = new ArrayList<ImportParser.Option>();
            String answer = cell(row, cols.get("answer"));
            var letters = new LinkedHashSet<Character>();
            for (char ch : answer.toUpperCase(Locale.ROOT).toCharArray()) if (ch >= 'A' && ch <= 'H') letters.add(ch);
            short order = 0;
            for (char l : "ABCDEFGH".toCharArray()) {
                String text = cell(row, cols.get("opt" + l));
                if (text.isBlank()) continue;
                options.add(new ImportParser.Option(text, false, order++));
            }
            QuestionType t = type(cell(row, cols.get("type")), !options.isEmpty());
            if (t == null) {
                if (!cell(row, cols.get("type")).isBlank()) { flags.add(ImportParser.Flag.LOW_CONFIDENCE); confidence -= 0.3; }
                t = !options.isEmpty() ? QuestionType.MULTIPLE_CHOICE : answer.isBlank() ? QuestionType.ESSAY : QuestionType.SHORT_ANSWER;
            }
            String expected = null;
            if (t == QuestionType.TRUE_FALSE && options.isEmpty()) {
                options.add(new ImportParser.Option("Đúng", false, (short) 0)); options.add(new ImportParser.Option("Sai", false, (short) 1));
            }
            if (t == QuestionType.MULTIPLE_CHOICE || t == QuestionType.TRUE_FALSE) {
                String ak = key(answer);
                int tf = ak.matches("d|dung|true|t|dung roi") ? 0 : ak.matches("s|sai|false|f") ? 1 : -1;
                var marked = new ArrayList<ImportParser.Option>();
                for (var o : options) {
                    char letter = (char) ('A' + o.displayOrder());
                    boolean correct = t == QuestionType.TRUE_FALSE && tf >= 0 ? o.displayOrder() == tf : letters.contains(letter) && ak.length() <= 15;
                    marked.add(new ImportParser.Option(o.content(), correct, o.displayOrder()));
                }
                options = marked;
                long n = options.stream().filter(ImportParser.Option::correct).count();
                if (n == 0) { flags.add(ImportParser.Flag.MISSING_CORRECT_ANSWER); confidence -= 0.4; }
                if (n > 1) { flags.add(ImportParser.Flag.MULTIPLE_ANSWERS); confidence -= 0.3; }
            } else {
                options.clear();
                if (t == QuestionType.SHORT_ANSWER) { expected = answer.isBlank() ? null : answer; if (expected == null) { flags.add(ImportParser.Flag.MISSING_CORRECT_ANSWER); confidence -= 0.4; } }
                if (t == QuestionType.ESSAY && answer.isBlank()) flags.add(ImportParser.Flag.NO_ANSWER_ESSAY);
            }
            var diff = difficulty(cell(row, cols.get("difficulty")), defaultDifficulty);
            if (diff == null) { diff = defaultDifficulty; flags.add(ImportParser.Flag.LOW_CONFIDENCE); confidence -= 0.1; }
            String explanation = cell(row, cols.get("explanation"));
            if (t == QuestionType.ESSAY && !answer.isBlank()) explanation = (explanation.isBlank() ? "" : explanation + "\n") + "Gợi ý chấm: " + answer;
            confidence = Math.max(0, Math.round(confidence * 100) / 100.0);
            if (confidence < ImportParser.REVIEW_THRESHOLD) flags.add(ImportParser.Flag.LOW_CONFIDENCE);
            out.add(new ImportParser.Parsed(content, t, diff, options, expected, explanation.isBlank() ? null : explanation, r + 1, SourceMethod.TEXT,
                    confidence, String.join(" | ", row).strip(), new ArrayList<>(flags), confidence < ImportParser.REVIEW_THRESHOLD
                    || flags.contains(ImportParser.Flag.MISSING_CORRECT_ANSWER) || flags.contains(ImportParser.Flag.MULTIPLE_ANSWERS)));
        }
        return out;
    }

    private static String cell(List<String> row, Integer c) { return c == null || c >= row.size() || row.get(c) == null ? "" : row.get(c).strip(); }

    private static List<List<String>> xlsxRows(byte[] bytes) {
        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = null;
            for (var s : wb) { if (key(s.getSheetName()).startsWith("cau hoi") || key(s.getSheetName()).startsWith("question")) { sheet = s; break; } }
            if (sheet == null) sheet = wb.getSheetAt(0);
            var fmt = new DataFormatter(new Locale("vi", "VN"));
            var eval = wb.getCreationHelper().createFormulaEvaluator();
            var rows = new ArrayList<List<String>>();
            for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 5000); r++) {
                var row = sheet.getRow(r);
                var cells = new ArrayList<String>();
                if (row != null) for (int c = 0; c < Math.min(row.getLastCellNum(), 40); c++) cells.add(row.getCell(c) == null ? "" : fmt.formatCellValue(row.getCell(c), eval));
                rows.add(cells);
            }
            return rows;
        } catch (Exception e) { throw new DocumentExtractor.ExtractionException("Không đọc được file Excel (.xlsx)"); }
    }

    private static List<List<String>> csvRows(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        char sep = text.lines().findFirst().map(l -> l.chars().filter(ch -> ch == ';').count() > l.chars().filter(ch -> ch == ',').count() ? ';' : ',').orElse(',');
        try (var reader = new CSVReaderBuilder(new java.io.StringReader(text)).withCSVParser(new com.opencsv.CSVParserBuilder().withSeparator(sep).build()).build()) {
            var rows = new ArrayList<List<String>>();
            String[] r;
            while ((r = reader.readNext()) != null && rows.size() < 5000) rows.add(Arrays.asList(r));
            return rows;
        } catch (Exception e) { throw new DocumentExtractor.ExtractionException("Không đọc được file CSV"); }
    }
}
