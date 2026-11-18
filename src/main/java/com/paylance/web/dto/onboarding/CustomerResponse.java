package com.paylance.web.dto.onboarding;

import com.paylance.domain.entity.Customer;

import java.time.LocalDateTime;

public record CustomerResponse(String id, String phone, String name, String email, String externalId, LocalDateTime createdAt) {

    public static CustomerResponse of(Customer c) {
        return new CustomerResponse(c.getId(), c.getPhone(), c.getName(), c.getEmail(), c.getExternalId(), c.getCreatedAt());
    }
}
