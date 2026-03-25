package com.filetransfer.repository;

import com.filetransfer.entity.TransferSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TransferSessionRepository extends JpaRepository<TransferSessionEntity, UUID> {
}