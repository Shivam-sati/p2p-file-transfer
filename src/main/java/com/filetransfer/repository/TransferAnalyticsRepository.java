package com.filetransfer.repository;

import com.filetransfer.entity.TransferAnalyticsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface TransferAnalyticsRepository extends JpaRepository<TransferAnalyticsEntity, UUID> {

    /**
     * Fetch all events within a time range, newest first.
     * Used by the event log endpoint.
     */
    @Query("""
            SELECT a FROM TransferAnalyticsEntity a
            WHERE a.recordedAt BETWEEN :from AND :to
            ORDER BY a.recordedAt DESC
            """)
    List<TransferAnalyticsEntity> findByTimeRange(
            @Param("from") Instant from,
            @Param("to") Instant to);

    /**
     * Count events of a specific type since a given timestamp.
     * Used for dashboard totals (uploads, downloads, p2p counts).
     */
    @Query("""
            SELECT COUNT(a) FROM TransferAnalyticsEntity a
            WHERE a.eventType = :type
            AND a.recordedAt > :since
            """)
    long countByEventTypeSince(
            @Param("type") TransferAnalyticsEntity.EventType type,
            @Param("since") Instant since);

    /**
     * Average transfer speed across all events in the time window.
     */
    @Query("""
            SELECT AVG(a.speedBps) FROM TransferAnalyticsEntity a
            WHERE a.speedBps IS NOT NULL
            AND a.recordedAt > :since
            """)
    Double avgSpeedSince(@Param("since") Instant since);

    /**
     * Peak transfer speed recorded in the time window.
     */
    @Query("""
            SELECT MAX(a.speedBps) FROM TransferAnalyticsEntity a
            WHERE a.speedBps IS NOT NULL
            AND a.recordedAt > :since
            """)
    Double peakSpeedSince(@Param("since") Instant since);

    /**
     * Count events grouped by transfer mode (SERVER vs P2P).
     * Returns List<Object[]> where [0]=mode, [1]=count.
     */
    @Query("""
            SELECT a.transferMode, COUNT(a)
            FROM TransferAnalyticsEntity a
            WHERE a.recordedAt > :since
            AND a.transferMode IS NOT NULL
            GROUP BY a.transferMode
            """)
    List<Object[]> countByModeSince(@Param("since") Instant since);

    /**
     * Total bytes uploaded in the time window.
     */
    @Query("""
            SELECT COALESCE(SUM(a.bytesAtEvent), 0)
            FROM TransferAnalyticsEntity a
            WHERE a.eventType = 'UPLOAD_COMPLETE'
            AND a.recordedAt > :since
            """)
    long sumBytesUploadedSince(@Param("since") Instant since);

    /**
     * Total bytes downloaded in the time window.
     */
    @Query("""
            SELECT COALESCE(SUM(a.bytesAtEvent), 0)
            FROM TransferAnalyticsEntity a
            WHERE a.eventType = 'DOWNLOAD_COMPLETE'
            AND a.recordedAt > :since
            """)
    long sumBytesDownloadedSince(@Param("since") Instant since);
}