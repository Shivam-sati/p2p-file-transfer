package com.filetransfer.controller;

import com.filetransfer.dto.response.AnalyticsDashboardResponse;
import com.filetransfer.dto.response.AnalyticsEventResponse;
import com.filetransfer.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Analytics REST API.
 *
 * GET /api/v1/analytics/dashboard — aggregated KPIs for the given time window
 * GET /api/v1/analytics/events — paginated raw event log
 *
 * All time parameters are ISO-8601 Instant strings, e.g. 2024-01-15T00:00:00Z
 * Default time window is the last 24 hours if no params are supplied.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    /**
     * GET /api/v1/analytics/dashboard
     *
     * Returns aggregated metrics for the frontend dashboard:
     * - Transfer counts (uploads, downloads, failures)
     * - P2P success / fallback rates
     * - Average and peak speed
     * - Total bytes moved
     * - Breakdown by transfer mode (SERVER vs P2P)
     *
     * Example:
     * GET
     * /api/v1/analytics/dashboard?from=2024-01-01T00:00:00Z&to=2024-01-02T00:00:00Z
     */
    @GetMapping("/dashboard")
    public ResponseEntity<AnalyticsDashboardResponse> getDashboard(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {

        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(24, ChronoUnit.HOURS);

        AnalyticsDashboardResponse response = analyticsService.getDashboardStats(effectiveFrom, effectiveTo);

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/analytics/events
     *
     * Returns the raw event log for a time range.
     * Useful for the detailed activity table in the dashboard.
     *
     * Example:
     * GET
     * /api/v1/analytics/events?from=2024-01-01T00:00:00Z&to=2024-01-02T00:00:00Z
     */
    @GetMapping("/events")
    public ResponseEntity<List<AnalyticsEventResponse>> getEvents(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {

        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(24, ChronoUnit.HOURS);

        List<AnalyticsEventResponse> events = analyticsService.getEventLog(effectiveFrom, effectiveTo);

        return ResponseEntity.ok(events);
    }
}