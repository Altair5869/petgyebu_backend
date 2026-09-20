package com.petgyebu.telo.budget.repository;

import com.petgyebu.telo.budget.domain.StatusThreshold;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatusThresholdRepository extends JpaRepository<StatusThreshold, Long> {
}
