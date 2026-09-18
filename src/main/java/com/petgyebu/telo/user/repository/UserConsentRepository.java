package com.petgyebu.telo.user.repository;

import com.petgyebu.telo.user.domain.UserConsent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {
}
