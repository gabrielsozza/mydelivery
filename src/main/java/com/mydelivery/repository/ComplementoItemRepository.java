package com.mydelivery.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mydelivery.model.ComplementoItem;

public interface ComplementoItemRepository extends JpaRepository<ComplementoItem, Long> {
}
