package com.paylance.web.dto.onboarding;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record ApiKeyCreateRequest(@Size(max = 150) String label, @Future LocalDateTime expiresAt) {
}
