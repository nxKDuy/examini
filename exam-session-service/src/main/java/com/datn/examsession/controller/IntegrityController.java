package com.datn.examsession.controller;

import com.datn.examsession.dto.IntegrityDto.*;
import com.datn.examsession.dto.IntegrityPolicy;
import com.datn.examsession.service.IntegrityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/sessions") @RequiredArgsConstructor
public class IntegrityController {
    private final IntegrityService service;
    private final ObjectMapper json;
    @GetMapping("/{id}/integrity/policy")
    public IntegrityPolicy policy(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestAttribute String actorRole) {
        return service.policy(id,actor,actorRole);
    }
    @PostMapping(value="/{id}/integrity/events",consumes="application/json")
    public Receipt ingest(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestAttribute String actorRole,HttpServletRequest request) {
        // Bounded even for chunked requests, before JSON allocation; no raw input is logged.
        try {
            byte[] body=request.getInputStream().readNBytes(4097);
            if(body.length>4096)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Integrity event body exceeds 4096 bytes");
            Ingest input=json.readerFor(Ingest.class)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .without(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .readValue(body);
            return service.ingest(id,actor,actorRole,input);
        } catch(IOException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid integrity event JSON");
        }
    }
    @GetMapping("/{id}/integrity/events")
    public Page<Event> sessionEvents(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestAttribute String actorRole,
            @RequestParam(required=false) EventType type,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return service.sessionEvents(id,actor,actorRole,type,page,size);
    }
    @GetMapping("/exam/{id}/integrity/events")
    public Page<Event> examEvents(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestAttribute String actorRole,
            @RequestParam(required=false) EventType type,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return service.examEvents(id,actor,actorRole,type,page,size);
    }
    @GetMapping("/{id}/integrity/summary")
    public Summary summary(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestAttribute String actorRole) {
        return service.summary(id,actor,actorRole);
    }
    @GetMapping("/{id}/integrity/audit")
    public Page<Audit> audit(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestAttribute String actorRole,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return service.audit(id,actor,actorRole,page,size);
    }
}
