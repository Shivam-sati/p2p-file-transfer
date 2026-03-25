package com.filetransfer.dto.response;
import com.filetransfer.entity.FileEntity;
import java.util.List;
import java.util.UUID;



public record FileInitResponse(
    UUID   fileId,
    long   chunkSizeBytes,
    int    totalChunks,
    String status,
    List<Integer> uploadedChunks   // always empty on init; included for symmetry with resume response
) {
    public static FileInitResponse from(FileEntity file, long chunkSizeBytes) {
        return new FileInitResponse(
            file.getId(),
            chunkSizeBytes,
            file.getTotalChunks(),
            file.getStatus().name(),
            List.of()
        );
    }
}