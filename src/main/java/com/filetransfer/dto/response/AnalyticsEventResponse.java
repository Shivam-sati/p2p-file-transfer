package com.filetransfer.dto.response;

import com.filetransfer.entity.TransferAnalyticsEntity;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a single analytics event row.
 * Used in the event log endpoint and time-series queries.
 */
@Data
@Builder
public class AnalyticsEventResponse {

    private UUID id;
    private UUID sessionId;
    private String eventType;
    private long bytesAtEvent;
    private Double speedBps;
    private String transferMode;
    private String errorCode;
    private Instant recordedAt;

    public static AnalyticsEventResponse from(TransferAnalyticsEntity entity) {
        return AnalyticsEventResponse.builder()
                .id(entity.getId())
                .sessionId(entity.getSession() != null ? entity.getSession().getId() : null)
                .eventType(entity.getEventType().name())
                .bytesAtEvent(entity.getBytesAtEvent())
                .speedBps(entity.getSpeedBps())
                .transferMode(entity.getTransferMode() != null ? entity.getTransferMode().name() : null)
                .errorCode(entity.getErrorCode())
                .recordedAt(entity.getRecordedAt())
                .build();
    }
}