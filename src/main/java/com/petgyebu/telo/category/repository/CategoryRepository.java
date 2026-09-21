package com.petgyebu.telo.category.repository;

import com.petgyebu.telo.category.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Short> {
}
