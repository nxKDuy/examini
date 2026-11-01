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
        if (k.matches("dung sai nhieu y|ds nhieu y|dung sai \\d y|dung sai tung y|nhieu y dung sai|true false group|tfg")) return QuestionType.TRUE_FALSE_GROUP;
        if (k.matches("nhieu dap an|chon nhieu|chon nhieu dap an|nhieu dap an dung|multiple response|multiple answers|mr")) return QuestionType.MULTIPLE_RESPONSE;
        if (k.matches("trac nghiem|tn|mcq|multiple choice|nhieu lua chon|chon dap an")) return QuestionType.MULTIPLE_CHOICE;
        if (k.matches("dung sai|d s|ds|true false|tf|dung/sai")) return QuestionType.TRUE_FALSE;
        if (k.matches("dien khuyet|dien vao cho trong|dien cho trong|dien tu|fill blank|fill in the blank|fill in the blanks|fill blanks|cloze|fb")) return QuestionType.FILL_BLANK;
        if (k.matches("noi cot|ghep cot|ghep noi|ghep doi|matching|match|mt")) return QuestionType.MATCHING;
        if (k.matches("tra loi ngan|dien|short answer|short|tln")) return QuestionType.SHORT_ANSWER;
        if (k.matches("tu luan|viet|essay|writing|tl")) return QuestionType.ESSAY;
        if (k.matches("noi|speaking|ghi am|van dap")) return QuestionType.SPEAKING;
        try { return QuestionType.valueOf(raw.strip().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
    }

    /**
     * Answers of a true/false group: "Đ, S, Đ, S", "ĐSĐS", "a) Đ b) S …" or "1 0 1 0"; null when it is not such a list.
     * Item labels (a), b. …) are dropped first so the letter D is never read as "Đúng".
     */
    static List<Boolean> truths(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.replaceAll("(?i)(^|[\\s,;|/])[a-h]\\s*[).:]", " ").strip();
        var tokens = new ArrayList<String>();
        for (String part : s.split("[\\s,;|/\\-]+")) {
            if (part.isBlank()) continue;
            String k = key(part);
            if (k.matches("[ds]{2,8}") && part.matches("[ĐđDdSs]+")) { for (char c : k.toCharArray()) tokens.add(String.valueOf(c)); continue; }
            tokens.add(k);
        }
        var out = new ArrayList<Boolean>();
        for (String t : tokens) {
            if (t.matches("d|dung|t|true|1|x")) out.add(true);
            else if (t.matches("s|sai|f|false|0")) out.add(false);
            else return null;
        }
        return out.isEmpty() ? null : out;
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
                int blanks = StructuredTypes.blanks(content);
                t = StructuredTypes.allPairs(options.stream().map(ImportParser.Option::content).toList()) ? QuestionType.MATCHING
                        : !options.isEmpty() ? QuestionType.MULTIPLE_CHOICE
                        : blanks > 1 && StructuredTypes.gapAnswers(answer).size() == blanks ? QuestionType.FILL_BLANK
                        : answer.isBlank() ? QuestionType.ESSAY : QuestionType.SHORT_ANSWER;
            }
            String expected = null;
            if (t == QuestionType.TRUE_FALSE && options.isEmpty()) {
                options.add(new ImportParser.Option("Đúng", false, (short) 0)); options.add(new ImportParser.Option("Sai", false, (short) 1));
            }
            if (t == QuestionType.FILL_BLANK) {
                // Gap answers: one option column per gap, or all of them in the answer cell ("Hà Nội; 1945").
                var gaps = options.isEmpty() ? StructuredTypes.gapAnswers(answer) : options.stream().map(ImportParser.Option::content).toList();
                options = new ArrayList<>();
                short gap = 0;
                for (var g : gaps) options.add(new ImportParser.Option(g, true, gap++));
                if (options.isEmpty() || options.size() != StructuredTypes.blanks(content)) { flags.add(ImportParser.Flag.MISSING_CORRECT_ANSWER); confidence -= 0.4; }
            } else if (t == QuestionType.MATCHING) {
                var pairs = new ArrayList<ImportParser.Option>();
                for (var o : options) {
                    String pair = StructuredTypes.normalizePair(o.content());
                    if (pair == null) { flags.add(ImportParser.Flag.LOW_CONFIDENCE); confidence -= 0.3; pair = o.content(); }
                    pairs.add(new ImportParser.Option(pair, true, o.displayOrder()));
                }
                options = pairs;
                if (options.size() < 2) { flags.add(ImportParser.Flag.MISSING_CORRECT_ANSWER); confidence -= 0.4; }
            } else if (t == QuestionType.TRUE_FALSE_GROUP) {
                var truths = truths(answer);
                if (truths == null || truths.size() != options.size()) { flags.add(ImportParser.Flag.MISSING_CORRECT_ANSWER); confidence -= 0.4; }
                else { var marked = new ArrayList<ImportParser.Option>(); for (var o : options) marked.add(new ImportParser.Option(o.content(), truths.get(o.displayOrder()), o.displayOrder())); options = marked; }
            } else if (t == QuestionType.MULTIPLE_RESPONSE) {
                var marked = new ArrayList<ImportParser.Option>();
                for (var o : options) marked.add(new ImportParser.Option(o.content(), letters.contains((char) ('A' + o.displayOrder())), o.displayOrder()));
                options = marked;
                if (options.stream().noneMatch(ImportParser.Option::correct)) { flags.add(ImportParser.Flag.MISSING_CORRECT_ANSWER); confidence -= 0.4; }
            } else if (t == QuestionType.MULTIPLE_CHOICE || t == QuestionType.TRUE_FALSE) {
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
