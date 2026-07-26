package com.emporia.ordermanagement.repository;

import com.emporia.ordermanagement.model.ProcessedCommand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedCommandRepository extends JpaRepository<ProcessedCommand, UUID> { }
