package com.datn.auth.controller;

import com.datn.auth.dto.ContactDto;
import com.datn.auth.service.ContactService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Service-to-service only; xác thực X-Service-Token ở InternalServiceAuthentication. */
@Hidden
@RestController
@RequestMapping("/auth/internal")
@RequiredArgsConstructor
public class InternalContactController {

    private final ContactService contactService;

    @PostMapping("/contacts")
    public List<ContactDto.Contact> contacts(@Valid @RequestBody ContactDto.ContactRequest req) {
        return contactService.contacts(req.userIds());
    }
}
