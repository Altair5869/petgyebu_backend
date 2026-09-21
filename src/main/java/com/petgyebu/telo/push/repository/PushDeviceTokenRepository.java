package com.petgyebu.telo.push.repository;

import com.petgyebu.telo.push.domain.PushDeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushDeviceTokenRepository extends JpaRepository<PushDeviceToken, Long> {
}
