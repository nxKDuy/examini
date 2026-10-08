package com.datn.auth.controller;

import com.datn.auth.dto.IdentityDto;
import com.datn.auth.service.IdentityResolutionService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Service-to-service only; xác thực X-Service-Token ở InternalServiceAuthentication. */
@Hidden
@RestController
@RequestMapping("/auth/internal/identities")
@RequiredArgsConstructor
public class InternalIdentityController {

    private final IdentityResolutionService identityService;

    @PostMapping("/resolve")
    public List<IdentityDto.Identity> resolve(@Valid @RequestBody IdentityDto.ResolveRequest req) {
        return identityService.resolve(req);
    }
}
