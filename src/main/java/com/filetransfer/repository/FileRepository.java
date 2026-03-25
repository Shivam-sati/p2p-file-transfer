package com.filetransfer.repository;

import com.filetransfer.entity.FileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FileRepository extends JpaRepository<FileEntity, UUID> {

    /** Find all files that have expired and are not yet deleted. */
    @Query("SELECT f FROM FileEntity f WHERE f.expiresAt < :now AND f.status <> 'DELETED'")
    List<FileEntity> findExpired(@Param("now") Instant now);

    /** Atomically increment uploadedChunks and return the new count. Used when a chunk lands. */
    @Modifying
    @Query("UPDATE FileEntity f SET f.uploadedChunks = f.uploadedChunks + 1 WHERE f.id = :id")
    void incrementUploadedChunks(@Param("id") UUID id);

    Optional<FileEntity> findByIdAndStatusNot(UUID id, FileEntity.Status status);
}