package com.filetransfer.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * Response DTO for GET /api/v1/analytics/dashboard
 * Aggregates all key metrics for the frontend dashboard.
 */
@Data
@Builder
public class AnalyticsDashboardResponse {

    // Transfer counts
    private long totalUploads;
    private long totalDownloads;
    private long failedUploads;
    private long failedDownloads;

    // P2P metrics
    private long p2pConnections;
    private long p2pFallbacks;
    private double p2pSuccessRatePercent;

    // Speed metrics
    private double avgSpeedBps;
    private double avgSpeedMbps;
    private double peakSpeedBps;

    // Storage metrics
    private long totalBytesUploaded;
    private long totalBytesDownloaded;

    // Transfer mode breakdown — e.g. { "SERVER": 120, "P2P": 45 }
    private Map<String, Long> transfersByMode;

    // Time period this report covers
    private Instant periodFrom;
    private Instant periodTo;
}