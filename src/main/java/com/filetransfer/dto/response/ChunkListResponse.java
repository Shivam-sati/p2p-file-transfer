package com.filetransfer.dto.response;
 
import com.filetransfer.entity.FileEntity;
import com.filetransfer.entity.ShareCodeEntity;
 
import java.time.Instant;
import java.util.List;
import java.util.UUID;
 

public record ChunkListResponse(
    UUID         fileId,
    List<Integer> uploadedChunks,
    int          totalChunks,
    String       status
) {
    public static ChunkListResponse from(FileEntity file, List<Integer> uploadedIndexes) {
        return new ChunkListResponse(
            file.getId(),
            uploadedIndexes,
            file.getTotalChunks(),
            file.getStatus().name()
        );
    }
}
 
 