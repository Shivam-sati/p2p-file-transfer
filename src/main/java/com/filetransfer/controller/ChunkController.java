package com.filetransfer.controller;

import com.filetransfer.dto.response.ChunkListResponse;
import com.filetransfer.dto.response.ChunkUploadResponse;
import com.filetransfer.dto.response.MergeResponse;
import com.filetransfer.entity.FileEntity;
import com.filetransfer.service.ChunkService;
import com.filetransfer.service.FileService;
import com.filetransfer.service.MergeService;
import com.filetransfer.service.progress.ProgressBroadcaster;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/files/{fileId}")
@RequiredArgsConstructor
public class ChunkController {

    private final ChunkService chunkService;
    private final FileService fileService;
    private final MergeService mergeService;
    private final ProgressBroadcaster progressBroadcaster; // Phase 4 — NEW injection

    /**
     * POST /api/v1/files/{fileId}/chunks/{chunkIndex}
     * After saving the chunk, broadcast upload progress over WebSocket.
     */
    @PostMapping("/chunks/{chunkIndex}")
    public ResponseEntity<ChunkUploadResponse> uploadChunk(
            @PathVariable UUID fileId,
            @PathVariable int chunkIndex,
            @RequestParam("file") MultipartFile data,
            @RequestHeader(value = "X-Chunk-MD5", required = false) String md5) throws IOException {

        var chunk = chunkService.uploadChunk(fileId, chunkIndex, data, md5);
        var file = fileService.getFile(fileId);

        // Phase 4: broadcast progress to all subscribers of /topic/progress/{fileId}
        progressBroadcaster.chunkUploaded(file, chunk.getChunkSize());

        return ResponseEntity.ok(new ChunkUploadResponse(
                chunkIndex,
                chunk.getStatus().name(),
                file.getUploadedChunks(),
                file.getTotalChunks()));
    }

    /**
     * GET /api/v1/files/{fileId}/chunks
     * Returns uploaded chunk indexes for resume detection.
     */
    @GetMapping("/chunks")
    public ResponseEntity<ChunkListResponse> listChunks(@PathVariable UUID fileId) {
        var file = fileService.getFile(fileId);
        var indexes = chunkService.getUploadedChunkIndexes(fileId);
        return ResponseEntity.ok(ChunkListResponse.from(file, indexes));
    }

    /**
     * POST /api/v1/files/{fileId}/merge
     * Triggers async merge and broadcasts MERGING status.
     */
    @PostMapping("/merge")
    public ResponseEntity<MergeResponse> triggerMerge(@PathVariable UUID fileId) {
        mergeService.triggerMerge(fileId);

        // Phase 4: let subscribers know merge has started
        progressBroadcaster.uploadMerging(fileId);

        return ResponseEntity.accepted().body(MergeResponse.accepted(fileId));
    }
}