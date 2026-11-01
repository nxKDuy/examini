package com.datn.questionbank.service;

import com.datn.questionbank.dto.Presentation;
import com.datn.questionbank.entity.Question;
import com.datn.questionbank.entity.QuestionMedia;
import com.datn.questionbank.repository.QuestionMediaRepository;
import com.datn.questionbank.security.QuestionActor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Images and audio for questions: upload by authors, read by any signed-in user who has the (random) media id —
 * the id only reaches students through the exam they are taking. The file type is decided from the bytes.
 */
@Service
public class MediaService {
    public static final int MAX_IMAGE_BYTES = 3 * 1024 * 1024, MAX_AUDIO_BYTES = 20 * 1024 * 1024;
    public static final int MAX_MEDIA = 10, MAX_PASSAGE = 20000;
    private final QuestionMediaRepository media;
    private final Path root;

    public MediaService(QuestionMediaRepository media, @Value("${question-bank.media-dir:${java.io.tmpdir}/examini-media}") String dir) {
        this.media = media; this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    private static ResponseStatusException error(HttpStatus s, String m) { return new ResponseStatusException(s, m); }

    /** Content type from magic bytes; null when it is not an accepted image or audio format. */
    static String sniff(byte[] b) {
        if (b.length < 12) return null;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "image/gif";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E') return "audio/wav";
        if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') return "audio/mpeg";
        if ((b[0] & 0xFF) == 0xFF && ((b[1] & 0xE0) == 0xE0)) return "audio/mpeg"; // MPEG frame sync
        if (b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') return "audio/ogg";
        if ((b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45 && (b[2] & 0xFF) == 0xDF && (b[3] & 0xFF) == 0xA3) return "audio/webm";
        if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return "audio/mp4"; // m4a/aac in MP4
        return null;
    }

    public record Uploaded(UUID id, String kind, String contentType, int size, String name) {}

    @Transactional
    public Uploaded upload(byte[] data, String originalName, QuestionActor actor) {
        if (actor == null || !actor.author()) throw error(HttpStatus.FORBIDDEN, "Chỉ giáo viên được tải media");
        if (data == null || data.length == 0) throw error(HttpStatus.BAD_REQUEST, "File trống");
        String type = sniff(data);
        if (type == null) throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Chỉ nhận ảnh JPEG/PNG/GIF/WebP hoặc âm thanh MP3/M4A/WAV/OGG/WebM");
        var kind = type.startsWith("image/") ? QuestionMedia.Kind.IMAGE : QuestionMedia.Kind.AUDIO;
        int max = kind == QuestionMedia.Kind.IMAGE ? MAX_IMAGE_BYTES : MAX_AUDIO_BYTES;
        if (data.length > max) throw error(HttpStatus.PAYLOAD_TOO_LARGE, kind == QuestionMedia.Kind.IMAGE ? "Ảnh tối đa 3 MB" : "File nghe tối đa 20 MB");
        UUID id = UUID.randomUUID();
        Path file = root.resolve(actor.userId().toString()).resolve(id.toString());
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(id + ".part");
            Files.write(tmp, data);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw error(HttpStatus.SERVICE_UNAVAILABLE, "Không lưu được file"); }
        String name = originalName == null ? null : originalName.replaceAll("[\\p{Cntrl}/\\\\]", "").strip();
        if (name != null && name.length() > 255) name = name.substring(name.length() - 255);
        media.save(QuestionMedia.builder().id(id).ownerId(actor.userId()).kind(kind).contentType(type).sizeBytes(data.length)
                .name(name == null || name.isBlank() ? null : name).storagePath(root.relativize(file).toString())
                .createdAt(Instant.now().truncatedTo(ChronoUnit.MICROS)).build());
        return new Uploaded(id, kind.name(), type, data.length, name);
    }

    public record File(String contentType, byte[] bytes) {}

    @Transactional(readOnly = true)
    public File read(UUID id) {
        var m = media.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy media"));
        Path file = root.resolve(m.getStoragePath()).normalize();
        if (!file.startsWith(root)) throw error(HttpStatus.NOT_FOUND, "Không tìm thấy media");
        try { return new File(m.getContentType(), Files.readAllBytes(file)); }
        catch (IOException e) { throw error(HttpStatus.NOT_FOUND, "File media đã bị xóa"); }
    }

    /** Drops empty parts, applies per-type defaults; null when nothing is left. */
    public Presentation normalize(Presentation p, Question.QuestionType type) {
        Integer record = type == Question.QuestionType.SPEAKING ? (p != null && p.maxRecordSeconds() != null ? p.maxRecordSeconds() : 120) : null;
        if (p == null) return record == null ? null : new Presentation(null, null, null, null, null, null, record);
        var passage = p.passage() == null || ((p.passage().content() == null || p.passage().content().isBlank())
                && (p.passage().media() == null || p.passage().media().isEmpty())) ? null
                : new Presentation.Passage(p.passage().key() == null ? UUID.randomUUID() : p.passage().key(),
                    blankToNull(p.passage().title()), blankToNull(p.passage().content()), emptyToNull(p.passage().media()));
        boolean essay = type == Question.QuestionType.ESSAY;
        var out = new Presentation(emptyToNull(p.media()), p.optionMedia() == null || p.optionMedia().isEmpty() ? null : new TreeMap<>(p.optionMedia()),
                passage, p.maxPlays(), essay ? p.minWords() : null, essay ? p.maxWords() : null, record);
        return out.empty() ? null : out;
    }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.strip(); }
    private static <T> List<T> emptyToNull(List<T> l) { return l == null || l.isEmpty() ? null : List.copyOf(l); }

    /** Every referenced file exists with the declared kind; option images point at real options; limits are sane. */
    @Transactional(readOnly = true)
    public void check(Question q) {
        var p = q.getPresentation();
        if (p == null) return;
        var all = new ArrayList<Presentation.Media>();
        if (p.media() != null) all.addAll(p.media());
        if (p.passage() != null && p.passage().media() != null) all.addAll(p.passage().media());
        if (p.media() != null && p.media().size() > MAX_MEDIA) throw new IllegalArgumentException("Tối đa " + MAX_MEDIA + " file đính kèm");
        if (p.passage() != null && p.passage().media() != null && p.passage().media().size() > 5) throw new IllegalArgumentException("Bài đọc tối đa 5 file đính kèm");
        var ids = new HashMap<UUID, String>();
        for (var m : all) {
            if (m == null || m.id() == null || !Set.of("IMAGE", "AUDIO").contains(m.kind())) throw new IllegalArgumentException("Media đính kèm không hợp lệ");
            if (m.name() != null && m.name().length() > 255) throw new IllegalArgumentException("Tên file quá dài");
            ids.put(m.id(), m.kind());
        }
        if (p.optionMedia() != null) {
            var orders = new HashSet<String>();
            q.getOptions().forEach(o -> orders.add(String.valueOf(o.getDisplayOrder())));
            for (var e : p.optionMedia().entrySet()) {
                if (!orders.contains(e.getKey()) || e.getValue() == null) throw new IllegalArgumentException("Ảnh lựa chọn không khớp lựa chọn nào");
                ids.put(e.getValue(), "IMAGE");
            }
        }
        if (!ids.isEmpty()) {
            var found = new HashMap<UUID, QuestionMedia>();
            media.findAllById(ids.keySet()).forEach(m -> found.put(m.getId(), m));
            for (var e : ids.entrySet()) {
                var m = found.get(e.getKey());
                if (m == null || !m.getKind().name().equals(e.getValue())) throw new IllegalArgumentException("File đính kèm không tồn tại hoặc sai loại");
            }
        }
        if (p.passage() != null) {
            if (p.passage().title() != null && p.passage().title().length() > 200) throw new IllegalArgumentException("Tiêu đề bài đọc tối đa 200 ký tự");
            if (p.passage().content() != null && p.passage().content().length() > MAX_PASSAGE) throw new IllegalArgumentException("Bài đọc tối đa 20000 ký tự");
        }
        boolean audio = all.stream().anyMatch(m -> "AUDIO".equals(m.kind()));
        if (p.maxPlays() != null && (!audio || p.maxPlays() < 1 || p.maxPlays() > 10)) throw new IllegalArgumentException("Số lần nghe chỉ đặt khi có file nghe, từ 1 đến 10");
        if (p.minWords() != null && (p.minWords() < 0 || p.minWords() > 5000)) throw new IllegalArgumentException("Số từ tối thiểu từ 0 đến 5000");
        if (p.maxWords() != null && (p.maxWords() < 1 || p.maxWords() > 5000)) throw new IllegalArgumentException("Số từ tối đa từ 1 đến 5000");
        if (p.minWords() != null && p.maxWords() != null && p.minWords() > p.maxWords()) throw new IllegalArgumentException("Số từ tối thiểu lớn hơn tối đa");
        if (p.maxRecordSeconds() != null && (q.getQuestionType() != Question.QuestionType.SPEAKING || p.maxRecordSeconds() < 10 || p.maxRecordSeconds() > 600))
            throw new IllegalArgumentException("Thời lượng ghi âm từ 10 đến 600 giây, chỉ cho câu Nói");
    }

    /** Passages the author already wrote, newest first, to reuse for another question of the same reading/listening group. */
    public static List<Presentation.Passage> distinctPassages(List<Presentation> list, int limit) {
        var seen = new LinkedHashMap<UUID, Presentation.Passage>();
        for (var p : list) if (p != null && p.passage() != null && p.passage().key() != null) seen.putIfAbsent(p.passage().key(), p.passage());
        return seen.values().stream().limit(limit).toList();
    }
}
