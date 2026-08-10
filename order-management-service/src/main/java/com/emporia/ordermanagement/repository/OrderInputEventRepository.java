package com.emporia.ordermanagement.repository;

import com.emporia.ordermanagement.model.OrderInputEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OrderInputEventRepository extends JpaRepository<OrderInputEvent, Long> {
    List<OrderInputEvent> findAllByOrderBySequenceIdAsc();
    List<OrderInputEvent> findByOrderBySequenceIdDesc(Pageable pageable);
    List<OrderInputEvent> findBySequenceIdGreaterThanOrderBySequenceIdAsc(Long sequenceId, Pageable pageable);
    @Query("select coalesce(max(event.sequenceId), 0) from OrderInputEvent event")
    long maxSequenceId();
}
