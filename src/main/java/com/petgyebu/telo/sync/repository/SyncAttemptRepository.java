package com.petgyebu.telo.sync.repository;

import com.petgyebu.telo.sync.domain.SyncAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncAttemptRepository extends JpaRepository<SyncAttempt, Long> {
}
