package com.filetransfer.controller;

import com.filetransfer.dto.response.ChunkListResponse;
import com.filetransfer.dto.response.ChunkUploadResponse;
import com.filetransfer.dto.response.MergeResponse;
import com.filetransfer.service.ChunkService;
import com.filetransfer.service.FileService;
import com.filetransfer.service.MergeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/files/{fileId}")
@RequiredArgsConstructor
public class ChunkController {

    private final ChunkService chunkService;
    private final FileService  fileService;
    private final MergeService mergeService;

    /**
     * POST /api/v1/files/{fileId}/chunks/{chunkIndex}
     *
     * Uploads a single chunk. The client should:
     *  - Send chunks in any order (order is determined by chunkIndex, not upload order)
     *  - Optionally include X-Chunk-MD5 header for per-chunk integrity
     *  - Retry failed chunks individually without restarting the whole upload
     */
    @PostMapping("/chunks/{chunkIndex}")
    public ResponseEntity<ChunkUploadResponse> uploadChunk(
            @PathVariable UUID   fileId,
            @PathVariable int    chunkIndex,
            @RequestParam("file") MultipartFile data,
            @RequestHeader(value = "X-Chunk-MD5", required = false) String md5) throws IOException {

        var chunk = chunkService.uploadChunk(fileId, chunkIndex, data, md5);
        var file  = fileService.getFile(fileId);

        return ResponseEntity.ok(new ChunkUploadResponse(
            chunkIndex,
            chunk.getStatus().name(),
            file.getUploadedChunks(),
            file.getTotalChunks()
        ));
    }

    /**
     * GET /api/v1/files/{fileId}/chunks
     *
     * Returns which chunk indexes have already been uploaded.
     * Called on page refresh or reconnect to resume an interrupted upload.
     * The client computes: missingIndexes = [0..N] minus uploadedChunks.
     */
    @GetMapping("/chunks")
    public ResponseEntity<ChunkListResponse> listChunks(@PathVariable UUID fileId) {
        var file    = fileService.getFile(fileId);
        var indexes = chunkService.getUploadedChunkIndexes(fileId);
        return ResponseEntity.ok(ChunkListResponse.from(file, indexes));
    }

    /**
     * POST /api/v1/files/{fileId}/merge
     *
     * Triggers asynchronous file assembly. Returns 202 Accepted immediately.
     * The client should poll GET /files/{fileId} until status = READY or FAILED.
     */
    @PostMapping("/merge")
    public ResponseEntity<MergeResponse> triggerMerge(@PathVariable UUID fileId) {
        mergeService.triggerMerge(fileId);
        return ResponseEntity.accepted().body(MergeResponse.accepted(fileId));
    }
}