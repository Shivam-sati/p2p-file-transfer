package com.filetransfer.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "files")
@Getter @Setter
@NoArgsConstructor
@ToString(exclude = "chunks")
public class FileEntity {

    public enum Status { UPLOADING, MERGING, READY, FAILED, DELETED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "file_name", nullable = false, length = 512)
    private String fileName;

    @Column(name = "original_name", nullable = false, length = 512)
    private String originalName;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "mime_type", length = 128)
    private String mimeType;

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    @Column(name = "storage_path", length = 1024)
    private String storagePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.UPLOADING;

    @Column(name = "total_chunks", nullable = false)
    private int totalChunks;

    @Column(name = "uploaded_chunks", nullable = false)
    private int uploadedChunks = 0;

    @Column(name = "uploader_session_id")
    private UUID uploaderSessionId;

    @Column(name = "uploader_ip", length = 45)
    private String uploaderIp;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ------- relationships -------

    @OneToMany(mappedBy = "file", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("chunkIndex ASC")
    private List<FileChunkEntity> chunks = new ArrayList<>();

    @OneToMany(mappedBy = "file", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ShareCodeEntity> shareCodes = new ArrayList<>();
}