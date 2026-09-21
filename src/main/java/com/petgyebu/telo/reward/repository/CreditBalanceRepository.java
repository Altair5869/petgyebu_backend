package com.petgyebu.telo.reward.repository;

import com.petgyebu.telo.reward.domain.CreditBalance;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 식별자가 {@code user_id}다. {@code credit_balances}의 PK가 곧 사용자 ID이기 때문이다.
 *
 * <p>잔액 갱신 시 필요한 {@code SELECT ... FOR UPDATE} 조회 메서드는 T-043·T-044에서 더한다.
 */
public interface CreditBalanceRepository extends JpaRepository<CreditBalance, Long> {
}
