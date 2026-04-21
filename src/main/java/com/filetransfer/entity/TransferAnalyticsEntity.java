package com.filetransfer.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfer_analytics")
@Getter
@Setter
public class TransferAnalyticsEntity {

    public enum EventType {
        UPLOAD_START,
        UPLOAD_COMPLETE,
        UPLOAD_FAILED,
        DOWNLOAD_START,
        DOWNLOAD_COMPLETE,
        DOWNLOAD_FAILED,
        P2P_CONNECTED,
        P2P_FALLBACK,
        P2P_CONNECTION_FAILED
    }

    public enum Mode {
        SERVER,
        P2P
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private TransferSessionEntity session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType eventType;

    @Column(nullable = false)
    private long bytesAtEvent;

    @Column(precision = 18, scale = 4)
    private Double speedBps;

    @Enumerated(EnumType.STRING)
    private Mode transferMode;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    private String errorCode;

    @Column(nullable = false)
    private Instant recordedAt = Instant.now();
}
