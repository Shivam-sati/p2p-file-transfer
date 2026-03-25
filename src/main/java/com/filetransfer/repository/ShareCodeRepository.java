package com.filetransfer.repository;

import com.filetransfer.entity.ShareCodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShareCodeRepository extends JpaRepository<ShareCodeEntity, UUID> {

    Optional<ShareCodeEntity> findByCode(String code);

    boolean existsByCode(String code);

    /** Atomically increment download counter. */
    @Modifying
    @Query("UPDATE ShareCodeEntity s SET s.downloadCount = s.downloadCount + 1 WHERE s.id = :id")
    void incrementDownloadCount(@Param("id") UUID id);
}