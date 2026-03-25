package com.filetransfer.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "file_chunks",
    uniqueConstraints = @UniqueConstraint(columnNames = {"file_id", "chunk_index"})
)
@Getter @Setter
@NoArgsConstructor
public class FileChunkEntity {

    public enum Status { PENDING, UPLOADED, MERGED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private FileEntity file;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "chunk_size", nullable = false)
    private long chunkSize;

    @Column(name = "byte_offset", nullable = false)
    private long byteOffset;

    @Column(name = "checksum_md5", length = 32)
    private String checksumMd5;

    @Column(name = "storage_path", nullable = false, length = 1024)
    private String storagePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}