package com.petgyebu.telo.shop.repository;

import com.petgyebu.telo.shop.domain.ShopItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopItemRepository extends JpaRepository<ShopItem, Long> {
}
