package com.datn.organization.controller;

import com.datn.organization.domain.Dto.*;
import com.datn.organization.service.AuthorizationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** Question Bank only; the dedicated service credential is enforced by RequestAuthentication. */
@RestController
@RequestMapping("/organizations/internal")
public class InternalAuthorizationController {
    private final AuthorizationService service;

    public InternalAuthorizationController(AuthorizationService service) { this.service = service; }

    @PostMapping("/authorize")
    public AuthorizeResponse authorize(@Valid @RequestBody AuthorizeRequest body) { return service.authorize(body); }

    @PostMapping("/memberships")
    public DiscoveryResponse memberships(@Valid @RequestBody DiscoveryRequest body) { return service.discover(body); }
}
