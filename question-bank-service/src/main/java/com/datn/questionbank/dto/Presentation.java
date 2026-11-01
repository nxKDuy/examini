package com.datn.questionbank.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * How a question is presented beyond its text (stored as JSON on the question and frozen in every snapshot):
 * attached images/audio, an image per option (keyed by option displayOrder), a reading passage shared by a group of
 * questions (same {@code passage.key}), how many times audio may be played, word limits for writing and the
 * longest voice answer for SPEAKING. Every part is optional.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Presentation(List<Media> media, Map<String, UUID> optionMedia, Passage passage,
                           Integer maxPlays, Integer minWords, Integer maxWords, Integer maxRecordSeconds) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Media(UUID id, String kind, String name) {}
    /** A reading/listening stimulus; questions with the same key are shown together under it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Passage(UUID key, String title, String content, List<Media> media) {}

    public boolean empty() {
        return (media == null || media.isEmpty()) && (optionMedia == null || optionMedia.isEmpty()) && passage == null
                && maxPlays == null && minWords == null && maxWords == null && maxRecordSeconds == null;
    }
}
