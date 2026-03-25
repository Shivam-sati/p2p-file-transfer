package com.filetransfer.dto.response;
 
import java.util.UUID;
public record MergeResponse(
    UUID   fileId,
    String status,   // "MERGING" — client must poll to confirm READY
    String message
) {
    public static MergeResponse accepted(UUID fileId) {
        return new MergeResponse(fileId, "MERGING", "Merge queued. Poll GET /files/{fileId} for status.");
    }
}