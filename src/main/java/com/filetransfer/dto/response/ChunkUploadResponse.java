package com.filetransfer.dto.response;
 

public record ChunkUploadResponse(
    int    chunkIndex,
    String status,          // "UPLOADED"
    int    uploadedCount,   // total uploaded so far (for client progress bar)
    int    totalChunks
) {}
 