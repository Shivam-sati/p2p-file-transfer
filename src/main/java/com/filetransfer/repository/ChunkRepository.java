package com.filetransfer.repository;

import com.filetransfer.entity.FileChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChunkRepository extends JpaRepository<FileChunkEntity, UUID> {

    /** All chunks for a file, sorted by index. Used during merge. */
    List<FileChunkEntity> findByFileIdOrderByChunkIndexAsc(UUID fileId);

    /** Indexes of uploaded chunks only — used to report resume state to client. */
    @Query("SELECT c.chunkIndex FROM FileChunkEntity c WHERE c.file.id = :fileId AND c.status = 'UPLOADED'")
    List<Integer> findUploadedIndexesByFileId(@Param("fileId") UUID fileId);

    Optional<FileChunkEntity> findByFileIdAndChunkIndex(UUID fileId, int chunkIndex);

    /** Count of uploaded chunks — used to verify completeness before merge. */
    long countByFileIdAndStatus(UUID fileId, FileChunkEntity.Status status);

    @Modifying
    @Query("UPDATE FileChunkEntity c SET c.status = 'MERGED' WHERE c.file.id = :fileId")
    void markAllMergedByFileId(@Param("fileId") UUID fileId);
}