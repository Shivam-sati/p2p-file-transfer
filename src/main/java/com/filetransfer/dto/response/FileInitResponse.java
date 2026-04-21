package com.filetransfer.dto.response;

import com.filetransfer.entity.FileEntity;

import java.util.List;
import java.util.UUID;

/**
 * Returned after POST /api/v1/files/init
 * Gives the client everything it needs to start uploading chunks.
 */
public record FileInitResponse(
        UUID fileId,
        long chunkSizeBytes,
        int totalChunks,
        String status,
        List<Integer> uploadedChunks // always empty on fresh init; populated on resume
) {
    public static FileInitResponse from(FileEntity file, long chunkSizeBytes) {
        return new FileInitResponse(
                file.getId(),
                chunkSizeBytes,
                file.getTotalChunks(),
                file.getStatus().name(),
                List.of());
    }
}