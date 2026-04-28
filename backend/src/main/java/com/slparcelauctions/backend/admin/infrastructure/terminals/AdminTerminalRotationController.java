package com.slparcelauctions.backend.admin.infrastructure.terminals;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/terminals")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminTerminalRotationController {

    private final AdminTerminalRotationService rotationService;

    @PostMapping("/rotate-secret")
    public TerminalRotationResponse rotate(@AuthenticationPrincipal AuthPrincipal principal) {
        return rotationService.rotate(principal.userId());
    }
}
