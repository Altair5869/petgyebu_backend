package com.petgyebu.telo.transaction.repository;

import com.petgyebu.telo.transaction.domain.TransferLink;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferLinkRepository extends JpaRepository<TransferLink, Long> {
}
