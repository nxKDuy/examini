package com.datn.questionbank.controller;

import com.datn.questionbank.dto.CopySyncDto.*;
import com.datn.questionbank.security.QuestionActor;
import com.datn.questionbank.service.CopySyncService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;

@RestController @RequiredArgsConstructor
public class CopySyncController {
    private final CopySyncService sync;
    private QuestionActor actor(HttpServletRequest r){
        if(r.getAttribute(QuestionActor.class.getName()) instanceof QuestionActor a)return a;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Yêu cầu xác thực");
    }
    @GetMapping("/questions/{id}/copy-sync")
    public QuestionPreview question(@PathVariable UUID id,HttpServletRequest r){return sync.question(id,actor(r));}
    @PostMapping("/questions/{id}/copy-sync")
    public QuestionPreview applyQuestion(@PathVariable UUID id,@Valid @RequestBody QuestionApplyRequest body,HttpServletRequest r){return sync.applyQuestion(id,body,actor(r));}
    @GetMapping("/questions/{id}/copy-sync/history")
    public List<SyncEvent> questionHistory(@PathVariable UUID id,HttpServletRequest r){return sync.questionHistory(id,actor(r));}
    @GetMapping("/question-sets/{id}/copy-sync")
    public SetPreview set(@PathVariable UUID id,HttpServletRequest r){return sync.set(id,actor(r));}
    @PostMapping("/question-sets/{id}/copy-sync")
    public SetPreview applySet(@PathVariable UUID id,@Valid @RequestBody SetApplyRequest body,HttpServletRequest r){return sync.applySet(id,body,actor(r));}
    @GetMapping("/question-sets/{id}/copy-sync/history")
    public List<SyncEvent> setHistory(@PathVariable UUID id,HttpServletRequest r){return sync.setHistory(id,actor(r));}
}
