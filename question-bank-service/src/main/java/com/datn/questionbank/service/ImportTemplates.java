package com.datn.questionbank.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/** Downloadable import templates: an Excel sheet (one question per row) and a Word file in the "Câu N:" format. */
public final class ImportTemplates {
    private ImportTemplates() {}

    static final List<String> HEADERS = List.of("Câu hỏi", "Loại", "Độ khó", "A", "B", "C", "D", "Đáp án", "Giải thích");
    static final List<List<String>> SAMPLES = List.of(
            List.of("Thủ đô của Việt Nam là thành phố nào?", "Trắc nghiệm", "Dễ", "Hà Nội", "Huế", "Đà Nẵng", "TP. Hồ Chí Minh", "A", "Hà Nội là thủ đô từ năm 1976."),
            List.of("Nước sôi ở 100 °C dưới áp suất 1 atm.", "Đúng/Sai", "Dễ", "", "", "", "", "Đúng", ""),
            List.of("2 + 3 × 4 = ?", "Trả lời ngắn", "Trung bình", "", "", "", "", "14", "Nhân trước, cộng sau."),
            List.of("Viết đoạn văn 150 chữ về bảo vệ môi trường.", "Tự luận", "Khó", "", "", "", "", "Có mở bài, 2 ý chính, kết bài", ""),
            List.of("Introduce yourself in one minute.", "Nói", "Trung bình", "", "", "", "", "", "Chấm phát âm, độ trôi chảy, từ vựng."));
    static final List<String> GUIDE = List.of(
            "HƯỚNG DẪN NHẬP CÂU HỎI TỪ EXCEL",
            "1. Mỗi dòng ở trang \"Câu hỏi\" là một câu. Giữ nguyên dòng tiêu đề; xóa các dòng ví dụ trước khi nhập.",
            "2. Cột Loại: Trắc nghiệm, Đúng/Sai, Trả lời ngắn, Tự luận, Nói. Để trống thì hệ thống tự đoán (có phương án → trắc nghiệm).",
            "3. Cột Độ khó: Dễ (Nhận biết), Trung bình (Thông hiểu), Khó (Vận dụng). Để trống dùng độ khó mặc định khi tải lên.",
            "4. Trắc nghiệm: điền phương án vào cột A, B, C, D (thêm cột E, F… nếu cần); cột Đáp án ghi chữ cái đúng, ví dụ B.",
            "5. Đúng/Sai: cột Đáp án ghi Đúng hoặc Sai (không cần cột A–D).",
            "6. Trả lời ngắn: cột Đáp án ghi đáp án chính xác. Tự luận/Nói: cột Đáp án (nếu có) là gợi ý chấm.",
            "7. Ảnh, file nghe và bài đọc dùng chung thêm sau khi nhập, ở màn hình sửa câu hỏi.",
            "8. Sau khi tải lên, mọi câu được đưa vào màn hình duyệt: kiểm tra, sửa rồi mới nhập vào kho.");

    public static byte[] excel() {
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var head = wb.createCellStyle(); var bold = wb.createFont(); bold.setBold(true); head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex()); head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var wrap = wb.createCellStyle(); wrap.setWrapText(true); wrap.setVerticalAlignment(VerticalAlignment.TOP);
            XSSFSheet sheet = wb.createSheet("Câu hỏi");
            var h = sheet.createRow(0);
            for (int i = 0; i < HEADERS.size(); i++) { var c = h.createCell(i); c.setCellValue(HEADERS.get(i)); c.setCellStyle(head); }
            for (int r = 0; r < SAMPLES.size(); r++) {
                var row = sheet.createRow(r + 1);
                for (int i = 0; i < SAMPLES.get(r).size(); i++) { var c = row.createCell(i); c.setCellValue(SAMPLES.get(r).get(i)); c.setCellStyle(wrap); }
            }
            int[] widths = {48, 14, 12, 18, 18, 18, 18, 18, 36};
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
            sheet.createFreezePane(0, 1);
            var dv = sheet.getDataValidationHelper();
            var types = dv.createValidation(dv.createExplicitListConstraint(new String[]{"Trắc nghiệm", "Đúng/Sai", "Trả lời ngắn", "Tự luận", "Nói"}), new CellRangeAddressList(1, 1000, 1, 1));
            types.setShowErrorBox(false); sheet.addValidationData(types);
            var levels = dv.createValidation(dv.createExplicitListConstraint(new String[]{"Dễ", "Trung bình", "Khó"}), new CellRangeAddressList(1, 1000, 2, 2));
            levels.setShowErrorBox(false); sheet.addValidationData(levels);
            var guide = wb.createSheet("Hướng dẫn");
            for (int i = 0; i < GUIDE.size(); i++) { var c = guide.createRow(i).createCell(0); c.setCellValue(GUIDE.get(i)); if (i == 0) c.setCellStyle(head); }
            guide.setColumnWidth(0, 120 * 256);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    static final List<String> WORD_GUIDE = List.of(
            "Mỗi câu bắt đầu bằng \"Câu N:\" (N là số thứ tự).",
            "Phương án trắc nghiệm mỗi dòng một phương án: A. … B. … C. … D. …",
            "Dòng \"Đáp án:\" ghi chữ cái đúng (trắc nghiệm), Đúng/Sai, hoặc đáp án ngắn. Không có đáp án và không có phương án → câu tự luận.",
            "Tùy chọn: \"Giải thích:\", \"Mức độ: Dễ / Trung bình / Khó\", \"Loại: Nói\" (câu nói) hoặc \"Loại: Tự luận\".",
            "Có thể lưu file này thành PDF rồi tải lên; PDF scan/ảnh chụp cũng được (hệ thống nhận chữ bằng OCR, cần duyệt kỹ hơn).");

    public static byte[] word() {
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            var title = doc.createParagraph(); title.setAlignment(ParagraphAlignment.CENTER);
            var tr = title.createRun(); tr.setBold(true); tr.setFontSize(14); tr.setText("MẪU NHẬP CÂU HỎI (WORD / PDF)");
            for (var g : WORD_GUIDE) { var p = doc.createParagraph(); p.setStyle("ListParagraph"); var r = p.createRun(); r.setItalic(true); r.setFontSize(10); r.setText("• " + g); }
            doc.createParagraph();
            String[][] blocks = {
                    {"Câu 1: Thủ đô của Việt Nam là thành phố nào?", "A. Hà Nội", "B. Huế", "C. Đà Nẵng", "D. TP. Hồ Chí Minh", "Đáp án: A", "Giải thích: Hà Nội là thủ đô từ năm 1976.", "Mức độ: Dễ"},
                    {"Câu 2: Nước sôi ở 100 °C dưới áp suất 1 atm.", "A. Đúng", "B. Sai", "Đáp án: A", "Mức độ: Dễ"},
                    {"Câu 3: 2 + 3 × 4 = ?", "Đáp án: 14", "Mức độ: Trung bình"},
                    {"Câu 4: Viết đoạn văn khoảng 150 chữ về bảo vệ môi trường.", "Loại: Tự luận", "Đáp án: Có mở bài, 2 ý chính, kết bài", "Mức độ: Khó"},
                    {"Câu 5: Introduce yourself in one minute.", "Loại: Nói", "Giải thích: Chấm phát âm, độ trôi chảy, từ vựng.", "Mức độ: Trung bình"}};
            for (var b : blocks) {
                for (int i = 0; i < b.length; i++) { var p = doc.createParagraph(); var r = p.createRun(); r.setText(b[i]); if (i == 0) r.setBold(true); }
                doc.createParagraph();
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
}
