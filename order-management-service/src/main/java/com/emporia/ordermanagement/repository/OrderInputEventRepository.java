package com.emporia.ordermanagement.repository;

import com.emporia.ordermanagement.model.OrderInputEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderInputEventRepository extends JpaRepository<OrderInputEvent, Long> {
    List<OrderInputEvent> findAllByOrderBySequenceIdAsc();
}