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

        @Query("""
                        SELECT a FROM TransferAnalyticsEntity a
                        WHERE a.recordedAt BETWEEN :from AND :to
                        ORDER BY a.recordedAt DESC
                        """)
        List<TransferAnalyticsEntity> findByTimeRange(
                        @Param("from") Instant from,
                        @Param("to") Instant to);

        @Query(value = """
                        SELECT COUNT(*)
                        FROM transfer_analytics
                        WHERE event_type = CAST(:eventType AS analytics_event_type)
                        AND recorded_at > :since
                        """, nativeQuery = true)
        long countByEventTypeSince(
                        @Param("eventType") String eventType,
                        @Param("since") Instant since);

        @Query("""
                        SELECT AVG(a.speedBps) FROM TransferAnalyticsEntity a
                        WHERE a.speedBps IS NOT NULL
                        AND a.recordedAt > :since
                        """)
        Double avgSpeedSince(@Param("since") Instant since);

        @Query("""
                        SELECT MAX(a.speedBps) FROM TransferAnalyticsEntity a
                        WHERE a.speedBps IS NOT NULL
                        AND a.recordedAt > :since
                        """)
        Double peakSpeedSince(@Param("since") Instant since);

        @Query("""
                        SELECT a.transferMode, COUNT(a)
                        FROM TransferAnalyticsEntity a
                        WHERE a.recordedAt > :since
                        AND a.transferMode IS NOT NULL
                        GROUP BY a.transferMode
                        """)
        List<Object[]> countByModeSince(@Param("since") Instant since);

        @Query(value = """
                        SELECT COALESCE(SUM(bytes_at_event), 0)
                        FROM transfer_analytics
                        WHERE event_type = CAST('UPLOAD_COMPLETE' AS analytics_event_type)
                        AND recorded_at > :since
                        """, nativeQuery = true)
        long sumBytesUploadedSince(@Param("since") Instant since);

        @Query(value = """
                        SELECT COALESCE(SUM(bytes_at_event), 0)
                        FROM transfer_analytics
                        WHERE event_type = CAST('DOWNLOAD_COMPLETE' AS analytics_event_type)
                        AND recorded_at > :since
                        """, nativeQuery = true)
        long sumBytesDownloadedSince(@Param("since") Instant since);
}