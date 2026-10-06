package com.datn.notification.controller;
import com.datn.notification.dto.NotificationDto.*;
import com.datn.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
/** Current user's inbox only: the recipient is always the authenticated actor. */
@RestController @RequiredArgsConstructor @RequestMapping("/notifications")
public class NotificationController {
    private final NotificationService service;
    @GetMapping public Page<NotificationResponse> list(@RequestParam(required=false) Boolean read,@PageableDefault(size=20) Pageable page,@RequestAttribute UUID actor){return service.list(actor,read,page);}
    @GetMapping("/unread-count") public UnreadCount unread(@RequestAttribute UUID actor){return service.unread(actor);}
    @PatchMapping("/{id}/read") public NotificationResponse read(@PathVariable UUID id,@RequestAttribute UUID actor){return service.markRead(id,actor);}
    @PatchMapping("/read-all") public ReadAllResult readAll(@RequestAttribute UUID actor){return service.markAllRead(actor);}
}
