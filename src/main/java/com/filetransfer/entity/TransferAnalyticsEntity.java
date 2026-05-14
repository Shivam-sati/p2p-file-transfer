package com.filetransfer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfer_analytics")
@Getter
@Setter
@NoArgsConstructor
public class TransferAnalyticsEntity {

    public enum EventType {
        UPLOAD_START, CHUNK_UPLOADED, UPLOAD_COMPLETE, UPLOAD_FAILED,
        DOWNLOAD_START, DOWNLOAD_PROGRESS, DOWNLOAD_COMPLETE, DOWNLOAD_FAILED,
        P2P_INITIATED, P2P_CONNECTED, P2P_FALLBACK, P2P_COMPLETE, FILE_EXPIRED
    }

    public enum Mode {
        SERVER, P2P
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private TransferSessionEntity session;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    @Column(name = "bytes_at_event", nullable = false)
    private long bytesAtEvent = 0;

    @Column(name = "speed_bps")
    private Double speedBps;

    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_mode")
    private Mode transferMode;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    @CreationTimestamp
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;
}