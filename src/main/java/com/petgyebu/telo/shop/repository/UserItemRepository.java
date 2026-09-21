package com.petgyebu.telo.shop.repository;

import com.petgyebu.telo.shop.domain.UserItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserItemRepository extends JpaRepository<UserItem, Long> {
}
