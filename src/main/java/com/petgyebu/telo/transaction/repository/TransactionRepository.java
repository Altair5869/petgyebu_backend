package com.petgyebu.telo.transaction.repository;

import com.petgyebu.telo.transaction.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
}
