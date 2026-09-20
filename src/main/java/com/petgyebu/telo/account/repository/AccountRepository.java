package com.petgyebu.telo.account.repository;

import com.petgyebu.telo.account.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {
}
