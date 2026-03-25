package com.filetransfer.dto.response;
 
import com.filetransfer.entity.FileEntity;
import com.filetransfer.entity.ShareCodeEntity;
 
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FileStatusResponse(
    UUID      fileId,
    String    originalName,
    long      fileSize,
    String    mimeType,
    String    status,
    int       totalChunks,
    int       uploadedChunks,
    Instant   createdAt,
    Instant   expiresAt
) {
    public static FileStatusResponse from(FileEntity file) {
        return new FileStatusResponse(
            file.getId(),
            file.getOriginalName(),
            file.getFileSize(),
            file.getMimeType(),
            file.getStatus().name(),
            file.getTotalChunks(),
            file.getUploadedChunks(),
            file.getCreatedAt(),
            file.getExpiresAt()
        );
    }
}