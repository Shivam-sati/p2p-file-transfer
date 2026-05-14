package com.filetransfer.service;

import com.filetransfer.dto.response.AnalyticsDashboardResponse;
import com.filetransfer.dto.response.AnalyticsEventResponse;
import com.filetransfer.entity.TransferAnalyticsEntity;
import com.filetransfer.entity.TransferSessionEntity;
import com.filetransfer.repository.TransferAnalyticsRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsService {

        private final TransferAnalyticsRepository analyticsRepository;

        @Transactional
        public void recordEvent(
                        TransferSessionEntity session,
                        TransferAnalyticsEntity.EventType type,
                        long bytesAtEvent,
                        Double speedBps,
                        TransferAnalyticsEntity.Mode mode) {

                TransferAnalyticsEntity event = new TransferAnalyticsEntity();

                event.setSession(session);
                event.setEventType(type);
                event.setBytesAtEvent(bytesAtEvent);
                event.setSpeedBps(speedBps);
                event.setTransferMode(mode);

                analyticsRepository.save(event);

                log.debug("Analytics event: type={} bytes={}", type, bytesAtEvent);
        }

        @Transactional
        public void recordError(
                        TransferSessionEntity session,
                        TransferAnalyticsEntity.EventType type,
                        String errorCode) {

                TransferAnalyticsEntity event = new TransferAnalyticsEntity();

                event.setSession(session);
                event.setEventType(type);
                event.setBytesAtEvent(0);
                event.setErrorCode(errorCode);

                analyticsRepository.save(event);
        }

        public AnalyticsDashboardResponse getDashboardStats(
                        Instant from,
                        Instant to) {

                long totalUploads = analyticsRepository.countByEventTypeSince(
                                TransferAnalyticsEntity.EventType.UPLOAD_COMPLETE,
                                from);

                long totalDownloads = analyticsRepository.countByEventTypeSince(
                                TransferAnalyticsEntity.EventType.DOWNLOAD_COMPLETE,
                                from);

                long failedUploads = analyticsRepository.countByEventTypeSince(
                                TransferAnalyticsEntity.EventType.UPLOAD_FAILED,
                                from);

                long failedDownloads = analyticsRepository.countByEventTypeSince(
                                TransferAnalyticsEntity.EventType.DOWNLOAD_FAILED,
                                from);

                long p2pConnections = analyticsRepository.countByEventTypeSince(
                                TransferAnalyticsEntity.EventType.P2P_CONNECTED,
                                from);

                long p2pFallbacks = analyticsRepository.countByEventTypeSince(
                                TransferAnalyticsEntity.EventType.P2P_FALLBACK,
                                from);

                double p2pSuccessRate = 0.0;

                long totalP2PAttempts = p2pConnections + p2pFallbacks;

                if (totalP2PAttempts > 0) {
                        p2pSuccessRate = (p2pConnections * 100.0) / totalP2PAttempts;
                }

                Double avgSpeed = analyticsRepository.avgSpeedSince(from);
                Double peakSpeed = analyticsRepository.peakSpeedSince(from);

                double avgBps = avgSpeed != null ? avgSpeed : 0.0;
                double peakBps = peakSpeed != null ? peakSpeed : 0.0;

                long bytesUploaded = analyticsRepository.sumBytesSince(
                                TransferAnalyticsEntity.EventType.UPLOAD_COMPLETE,
                                from);

                long bytesDownloaded = analyticsRepository.sumBytesSince(
                                TransferAnalyticsEntity.EventType.DOWNLOAD_COMPLETE,
                                from);

                List<Object[]> modeRows = analyticsRepository.countByModeSince(from);

                Map<String, Long> transfersByMode = new HashMap<>();

                for (Object[] row : modeRows) {

                        String modeName = row[0] != null
                                        ? row[0].toString()
                                        : "UNKNOWN";

                        Long count = (Long) row[1];

                        transfersByMode.put(modeName, count);
                }

                return AnalyticsDashboardResponse.builder()
                                .totalUploads(totalUploads)
                                .totalDownloads(totalDownloads)
                                .failedUploads(failedUploads)
                                .failedDownloads(failedDownloads)
                                .p2pConnections(p2pConnections)
                                .p2pFallbacks(p2pFallbacks)
                                .p2pSuccessRatePercent(p2pSuccessRate)
                                .avgSpeedBps(avgBps)
                                .avgSpeedMbps(avgBps / 1_000_000.0)
                                .peakSpeedBps(peakBps)
                                .totalBytesUploaded(bytesUploaded)
                                .totalBytesDownloaded(bytesDownloaded)
                                .transfersByMode(transfersByMode)
                                .periodFrom(from)
                                .periodTo(to)
                                .build();
        }

        public List<AnalyticsEventResponse> getEventLog(
                        Instant from,
                        Instant to) {

                return analyticsRepository.findByTimeRange(from, to)
                                .stream()
                                .map(AnalyticsEventResponse::from)
                                .toList();
        }
}