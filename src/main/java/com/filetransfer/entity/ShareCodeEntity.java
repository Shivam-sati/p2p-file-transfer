package com.filetransfer.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "share_codes")
@Getter @Setter
@NoArgsConstructor
public class ShareCodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private FileEntity file;

    @Column(nullable = false, unique = true, length = 16)
    private String code;

    @Column(name = "max_downloads")
    private Integer maxDownloads;     // null = unlimited

    @Column(name = "download_count", nullable = false)
    private int downloadCount = 0;

    @Column(name = "password_protected", nullable = false)
    private boolean passwordProtected = false;

    @Column(name = "password_hash", length = 128)
    private String passwordHash;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Returns true if this share link is still usable. */
    public boolean isValid() {
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) return false;
        if (maxDownloads != null && downloadCount >= maxDownloads) return false;
        return true;
    }
}