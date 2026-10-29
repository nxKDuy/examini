package com.datn.questionbank.service;

import com.datn.questionbank.entity.QuestionImportItem.SourceMethod;
import com.datn.questionbank.entity.QuestionImportJob.FileKind;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBrType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Turns an uploaded document into page-tagged lines; OCR (local Tesseract CLI) only for pages without a text layer. */
@Component
public class DocumentExtractor {
    public static final int MAX_PAGES = 30;
    static final int MIN_TEXT_CHARS = 20;
    private final String tesseract;
    private final String languages;
    private final long timeoutSeconds;

    public DocumentExtractor(@Value("${question-bank.import.tesseract-path:tesseract}") String tesseract,
            @Value("${question-bank.import.ocr-languages:vie+eng}") String languages,
            @Value("${question-bank.import.ocr-timeout-seconds:90}") long timeoutSeconds) {
        this.tesseract = tesseract; this.languages = languages; this.timeoutSeconds = timeoutSeconds;
    }

    /** One physical line of text; confidence is Tesseract's 0–100 word mean for OCR, null for a text layer. */
    public record Line(String text, int page, SourceMethod method, Double confidence) {}
    public record Extraction(List<Line> lines, int pageCount, int ocrPageCount, List<String> warnings) {}
    public static class ExtractionException extends RuntimeException {
        public ExtractionException(String message) { super(message); }
    }

    public static FileKind detect(byte[] bytes, String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (starts(bytes, 0x25, 0x50, 0x44, 0x46)) return FileKind.PDF;
        if (starts(bytes, 0x89, 0x50, 0x4E, 0x47) || starts(bytes, 0xFF, 0xD8, 0xFF)) return FileKind.IMAGE;
        if (starts(bytes, 0x50, 0x4B, 0x03, 0x04) && name.endsWith(".docx")) return FileKind.DOCX;
        return null;
    }
    private static boolean starts(byte[] bytes, int... magic) {
        if (bytes.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if ((bytes[i] & 0xFF) != magic[i]) return false;
        return true;
    }

    public Extraction extract(byte[] bytes, FileKind kind) {
        return switch (kind) {
            case DOCX -> docx(bytes);
            case PDF -> pdf(bytes);
            case IMAGE -> image(bytes);
            case AI -> throw new IllegalArgumentException("AI jobs have no document to extract");
        };
    }

    private Extraction docx(byte[] bytes) {
        var lines = new ArrayList<Line>();
        int page = 1;
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    if (paragraph.isPageBreak() && !lines.isEmpty()) page++;
                    var text = new StringBuilder();
                    for (XWPFRun run : paragraph.getRuns()) {
                        // Word's own pagination: rendered breaks are saved by Word; explicit breaks are authored.
                        int breaks = run.getCTR().sizeOfLastRenderedPageBreakArray();
                        for (var br : run.getCTR().getBrList()) if (br.getType() == STBrType.PAGE) breaks++;
                        if (breaks > 0 && (!lines.isEmpty() || !text.isEmpty())) {
                            if (!text.isEmpty()) { split(text.toString(), page, lines); text.setLength(0); }
                            page += breaks;
                        }
                        text.append(run.text());
                    }
                    split(text.toString(), page, lines);
                } else if (element instanceof XWPFTable table) {
                    for (var row : table.getRows()) for (var cell : row.getTableCells()) split(cell.getText(), page, lines);
                }
                if (page > MAX_PAGES) throw new ExtractionException("Tài liệu vượt " + MAX_PAGES + " trang");
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ExtractionException x) throw x;
            throw new ExtractionException("Không đọc được tệp DOCX");
        }
        return new Extraction(lines, page, 0, List.of());
    }

    private Extraction pdf(byte[] bytes) {
        var lines = new ArrayList<Line>();
        var warnings = new ArrayList<String>();
        int ocrPages = 0;
        try (var document = Loader.loadPDF(bytes)) {
            int pages = document.getNumberOfPages();
            if (pages > MAX_PAGES) throw new ExtractionException("Tài liệu vượt " + MAX_PAGES + " trang");
            var stripper = new PDFTextStripper();
            PDFRenderer renderer = null;
            for (int p = 1; p <= pages; p++) {
                stripper.setStartPage(p); stripper.setEndPage(p);
                String text = stripper.getText(document);
                if (text.replaceAll("\\s", "").length() >= MIN_TEXT_CHARS) { split(text, p, lines); continue; }
                if (renderer == null) renderer = new PDFRenderer(document);
                var image = renderer.renderImageWithDPI(p - 1, 300, ImageType.GRAY);
                var out = new ByteArrayOutputStream();
                ImageIO.write(image, "png", out);
                lines.addAll(ocr(out.toByteArray(), p, warnings));
                ocrPages++;
            }
            return new Extraction(lines, pages, ocrPages, warnings);
        } catch (IOException e) {
            throw new ExtractionException("Không đọc được tệp PDF");
        }
    }

    private Extraction image(byte[] bytes) {
        var warnings = new ArrayList<String>();
        return new Extraction(ocr(bytes, 1, warnings), 1, 1, warnings);
    }

    private static void split(String text, int page, List<Line> out) {
        for (String raw : text.split("\\R")) {
            String line = raw.replace(' ', ' ').strip();
            if (!line.isEmpty()) out.add(new Line(line, page, SourceMethod.TEXT, null));
        }
    }

    /** Tesseract TSV: words grouped by (block, paragraph, line); confidence -1 marks non-word rows. */
    List<Line> ocr(byte[] png, int page, List<String> warnings) {
        Path input = null, output = null;
        try {
            input = Files.createTempFile("qb-ocr-", ".img");
            output = Files.createTempFile("qb-ocr-", ".tsv");
            Files.write(input, png);
            var process = new ProcessBuilder(tesseract, input.toString(), "stdout", "-l", languages, "--psm", "4", "tsv")
                    .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("timeout"); }
            if (process.exitValue() != 0) throw new IOException("exit " + process.exitValue());
            return tsv(Files.readString(output, StandardCharsets.UTF_8), page);
        } catch (IOException e) {
            warnings.add("Trang " + page + ": OCR không khả dụng hoặc lỗi (" + e.getMessage() + ")");
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExtractionException("OCR bị gián đoạn");
        } finally {
            for (var file : new Path[] { input, output })
                if (file != null) try { Files.deleteIfExists(file); } catch (IOException ignored) {}
        }
    }

    static List<Line> tsv(String tsv, int page) {
        var grouped = new LinkedHashMap<String, List<String[]>>();
        for (String row : tsv.split("\\R")) {
            String[] c = row.split("\t", -1);
            if (c.length < 12 || !"5".equals(c[0]) || c[11].isBlank()) continue;
            grouped.computeIfAbsent(c[2] + ":" + c[3] + ":" + c[4], k -> new ArrayList<>()).add(c);
        }
        var lines = new ArrayList<Line>();
        for (var words : grouped.values()) {
            var text = new StringJoiner(" ");
            double sum = 0; int counted = 0;
            for (var w : words) {
                text.add(w[11].strip());
                double conf = Double.parseDouble(w[10]);
                if (conf >= 0) { sum += conf; counted++; }
            }
            lines.add(new Line(text.toString(), page, SourceMethod.OCR, counted == 0 ? 0.0 : sum / counted));
        }
        return lines;
    }
}
