package com.petgyebu.telo.user.repository;

import com.petgyebu.telo.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
