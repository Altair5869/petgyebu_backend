package com.petgyebu.telo.budget.repository;

import com.petgyebu.telo.budget.domain.BudgetPeriod;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetPeriodRepository extends JpaRepository<BudgetPeriod, Long> {
}
