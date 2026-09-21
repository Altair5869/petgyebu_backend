package com.petgyebu.telo.push.repository;

import com.petgyebu.telo.push.domain.PushLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushLogRepository extends JpaRepository<PushLog, Long> {
}
