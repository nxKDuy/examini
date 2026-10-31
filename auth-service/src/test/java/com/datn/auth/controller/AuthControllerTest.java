package com.datn.auth.controller;

import com.datn.auth.dto.AuthRequest;
import com.datn.auth.dto.AuthResponse;
import com.datn.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {
    @Autowired MockMvc mvc;
    @MockBean AuthService authService;
    @MockBean com.datn.auth.service.PasswordResetService passwordReset;
    @MockBean com.datn.auth.service.AccountService accounts;

    @Test
    void personalTeacherRegistrationContractReachesService() throws Exception {
        when(authService.register(any())).thenReturn(new AuthResponse.TokenResponse("access", "refresh", 900, "Bearer"));

        mvc.perform(post("/auth/register").contentType("application/json").content("""
                {"email":"teacher@example.com","password":"password","fullName":"Teacher",
                 "accountType":"PERSONAL","persona":"TEACHER"}
                """))
                .andExpect(status().isCreated());

        var request = org.mockito.ArgumentCaptor.forClass(AuthRequest.RegisterRequest.class);
        verify(authService).register(request.capture());
        assertEquals(AuthRequest.RegisterRequest.Persona.TEACHER, request.getValue().persona());
        assertEquals(AuthRequest.RegisterRequest.AccountType.PERSONAL, request.getValue().accountType());
    }

    @Test
    void legacyAdminRegistrationPayloadIsRejected() throws Exception {
        when(authService.register(any())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));

        mvc.perform(post("/auth/register").contentType("application/json").content("""
                {"email":"attacker@example.com","password":"password","fullName":"Attacker","role":"ADMIN"}
                """))
                .andExpect(status().isForbidden());
    }
}
