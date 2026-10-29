package com.datn.examsession.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Files of camera proctoring on a local volume: {root}/{examId}/{sessionId}/snapshots/{eventId}.jpg and
 * {root}/{examId}/{sessionId}/video/{seq}.webm. Paths are built only from server UUIDs and integers.
 */
@Component
public class CameraStorage {
    private final Path root;

    public CameraStorage(@Value("${exam.camera.storage-dir:${java.io.tmpdir}/examini-camera}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    private Path sessionDir(UUID exam, UUID session) { return root.resolve(exam.toString()).resolve(session.toString()); }

    /** @return the stored path relative to the root */
    public String saveSnapshot(UUID exam, UUID session, UUID event, byte[] jpeg) throws IOException {
        Path file = sessionDir(exam, session).resolve("snapshots").resolve(event + ".jpg");
        write(file, jpeg);
        return root.relativize(file).toString();
    }

    public byte[] readSnapshot(String relative) throws IOException {
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) throw new IOException("Outside storage root");
        return Files.readAllBytes(file);
    }

    /** Writes chunk {@code seq} once; a retried upload of the same seq is ignored. @return true if newly written */
    public boolean saveChunk(UUID exam, UUID session, int seq, byte[] data) throws IOException {
        Path file = sessionDir(exam, session).resolve("video").resolve(String.format("%06d.webm", seq));
        if (Files.exists(file)) return false;
        write(file, data);
        return true;
    }

    /** Chunk files in order; MediaRecorder chunks of one recording concatenate into one playable WebM. */
    public List<Path> chunks(UUID exam, UUID session) throws IOException {
        Path dir = sessionDir(exam, session).resolve("video");
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().matches("\\d{6}\\.webm")).sorted().toList();
        }
    }

    public long size(List<Path> files) throws IOException { long n = 0; for (Path f : files) n += Files.size(f); return n; }

    public void copyChunks(List<Path> files, OutputStream out) throws IOException { for (Path f : files) Files.copy(f, out); }

    public record Deleted(int files, long bytes) {}

    /** Deletes every snapshot and video chunk of one attempt (idempotent). */
    public Deleted deleteSession(UUID exam, UUID session) throws IOException {
        Path dir = sessionDir(exam, session);
        if (!Files.isDirectory(dir)) return new Deleted(0, 0);
        int files = 0; long bytes = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isRegularFile(p)) { files++; bytes += Files.size(p); }
                Files.deleteIfExists(p);
            }
        }
        Path examDir = dir.getParent();
        try (Stream<Path> rest = Files.list(examDir)) { if (rest.findAny().isEmpty()) Files.deleteIfExists(examDir); }
        catch (IOException | UncheckedIOException ignored) { /* another attempt of the exam is being written */ }
        return new Deleted(files, bytes);
    }

    private static void write(Path file, byte[] data) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".part");
        Files.write(tmp, data);
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
