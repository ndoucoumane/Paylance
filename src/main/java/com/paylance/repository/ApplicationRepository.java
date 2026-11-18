package com.paylance.repository;

import com.paylance.domain.entity.Application;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApplicationRepository extends JpaRepository<Application, String> {

    boolean existsBySlug(String slug);

    List<Application> findAllByOrderByCreatedAtAsc();
}
