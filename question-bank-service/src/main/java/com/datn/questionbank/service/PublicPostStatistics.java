package com.datn.questionbank.service;

import com.datn.questionbank.dto.BankDto.PublicViewResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component @RequiredArgsConstructor
class PublicPostStatistics {
    private final NamedParameterJdbcTemplate jdbc;

    record Counts(long day, long month, long year, long total) {}

    Counts copies(UUID id) {
        var row=jdbc.queryForMap("""
            SELECT count(DISTINCT e.actor_user_id) FILTER (WHERE e.copied_at >= timezone('UTC',CURRENT_TIMESTAMP)-INTERVAL '24 hours') AS day,
                   count(DISTINCT e.actor_user_id) FILTER (WHERE e.copied_at >= timezone('UTC',CURRENT_TIMESTAMP)-INTERVAL '30 days') AS month,
                   count(DISTINCT e.actor_user_id) FILTER (WHERE e.copied_at >= timezone('UTC',CURRENT_TIMESTAMP)-INTERVAL '365 days') AS year,
                   count(DISTINCT e.actor_user_id) AS total
            FROM public_question_copy_events e JOIN public_questions p ON p.id=e.publication_id
            WHERE e.publication_id=:id AND e.actor_user_id<>p.source_owner_user_id
            """,Map.of("id",id));
        return counts(row);
    }

    Counts views(UUID id) {
        var row=jdbc.queryForMap("""
            SELECT count(*) FILTER (WHERE e.created_at >= timezone('UTC',CURRENT_TIMESTAMP)-INTERVAL '24 hours') AS day,
                   count(*) FILTER (WHERE e.created_at >= timezone('UTC',CURRENT_TIMESTAMP)-INTERVAL '30 days') AS month,
                   count(*) FILTER (WHERE e.created_at >= timezone('UTC',CURRENT_TIMESTAMP)-INTERVAL '365 days') AS year,
                   count(*) AS total
            FROM public_question_view_events e JOIN public_questions p ON p.id=e.publication_id
            WHERE e.publication_id=:id AND e.actor_user_id<>p.source_owner_user_id
            """,Map.of("id",id));
        return counts(row);
    }

    PublicViewResponse viewResponse(UUID id) {
        var c=views(id);
        return new PublicViewResponse(id,c.day(),c.month(),c.year(),c.total());
    }

    private Counts counts(Map<String,Object> row) {
        return new Counts(((Number)row.get("day")).longValue(),((Number)row.get("month")).longValue(),
                ((Number)row.get("year")).longValue(),((Number)row.get("total")).longValue());
    }
}
