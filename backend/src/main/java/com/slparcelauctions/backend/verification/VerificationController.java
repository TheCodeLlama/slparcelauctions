package com.slparcelauctions.backend.verification;

import java.util.Optional;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.verification.dto.ActiveCodeResponse;
import com.slparcelauctions.backend.verification.dto.GenerateCodeResponse;
import com.slparcelauctions.backend.verification.exception.CodeNotFoundException;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/verification")
@RequiredArgsConstructor
public class VerificationController {

    private final VerificationCodeService service;

    @GetMapping("/active")
    public ActiveCodeResponse getActive(@AuthenticationPrincipal AuthPrincipal principal) {
        Optional<ActiveCodeResponse> active = service.findActive(
                principal.userId(), VerificationCodeType.PLAYER);
        return active.orElseThrow(
                () -> new CodeNotFoundException("(no active code)"));
    }

    @PostMapping("/generate")
    public GenerateCodeResponse generate(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.generate(principal.userId(), VerificationCodeType.PLAYER);
    }
}
