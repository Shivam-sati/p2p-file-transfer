// ─── Request DTOs ─────────────────────────────────────────────────────────────

package com.filetransfer.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class FileInitRequest {

    @NotBlank(message = "originalName is required")
    @Size(max = 512)
    private String originalName;

    @Positive(message = "fileSize must be positive")
    private long fileSize;

    @Size(max = 128)
    private String mimeType;

    @Positive(message = "totalChunks must be positive")
    private int totalChunks;

    @NotBlank
    @Pattern(regexp = "^[a-fA-F0-9]{64}$", message = "checksumSha256 must be a valid SHA-256 hex string")
    private String checksumSha256;
}