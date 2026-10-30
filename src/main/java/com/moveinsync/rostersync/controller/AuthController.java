package com.moveinsync.rostersync.controller;

import com.moveinsync.rostersync.dto.Dtos.LoginRequest;
import com.moveinsync.rostersync.dto.Dtos.TokenResponse;
import com.moveinsync.rostersync.dto.Dtos.WhoAmI;
import com.moveinsync.rostersync.exception.ApiException;
import com.moveinsync.rostersync.security.CurrentUser;
import com.moveinsync.rostersync.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authManager;
    private final JwtService jwt;

    public AuthController(AuthenticationManager authManager, JwtService jwt) {
        this.authManager = authManager;
        this.jwt = jwt;
    }


    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        Authentication auth;
        try {
            auth = authManager.authenticate(new UsernamePasswordAuthenticationToken(req.username(), req.password()));
        } catch (AuthenticationException e) {
            // same answer for unknown user and wrong password: no user enumeration
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid username or password");
        }
        CurrentUser user = (CurrentUser) auth.getPrincipal();
        return new TokenResponse(jwt.issue(user), "Bearer", jwt.ttlSeconds());
    }


    @GetMapping("/me")
    public WhoAmI me(@AuthenticationPrincipal CurrentUser user) {
        return new WhoAmI(user.username(), user.role().name(), user.tenantId(), user.employeeId());
    }
}
