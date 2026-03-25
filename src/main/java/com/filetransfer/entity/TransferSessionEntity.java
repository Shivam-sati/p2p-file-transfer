package com.filetransfer.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfer_sessions")
@Getter @Setter
@NoArgsConstructor
public class TransferSessionEntity {

    public enum Type      { SERVER, P2P }
    public enum Direction { UPLOAD, DOWNLOAD }
    public enum Status    { ACTIVE, COMPLETED, FAILED, TIMED_OUT, PEER_FALLBACK }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private FileEntity file;

    @Enumerated(EnumType.STRING)
    @Column(name = "session_type", nullable = false)
    private Type sessionType = Type.SERVER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(name = "peer_id", length = 128)
    private String peerId;

    @Column(name = "signaling_data", columnDefinition = "TEXT")
    private String signalingData;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "bytes_transferred", nullable = false)
    private long bytesTransferred = 0;

    @Column(name = "avg_speed_bps")
    private Double avgSpeedBps;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}