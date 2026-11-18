package com.paylance.repository;

import com.paylance.domain.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, String> {

    boolean existsBySlug(String slug);
}
