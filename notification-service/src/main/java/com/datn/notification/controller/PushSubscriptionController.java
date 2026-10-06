package com.datn.notification.controller;
import com.datn.notification.delivery.PushSubscriptions;
import com.datn.notification.delivery.PushSubscriptions.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
/** Browser push registration for the authenticated user only; consent UI belongs to frontend/app. */
@RestController @RequiredArgsConstructor @RequestMapping("/notifications/push")
public class PushSubscriptionController {
    private final PushSubscriptions service;
    @GetMapping("/public-key") public PublicKey publicKey(){return service.publicKey();}
    @PostMapping("/subscriptions") public ResponseEntity<Registered> subscribe(@RequestBody SubscribeRequest req,@RequestAttribute UUID actor){return ResponseEntity.status(HttpStatus.CREATED).body(service.subscribe(actor,req));}
    @DeleteMapping("/subscriptions/{id}") public ResponseEntity<Void> unsubscribe(@PathVariable UUID id,@RequestAttribute UUID actor){service.unsubscribe(actor,id);return ResponseEntity.noContent().build();}
}
