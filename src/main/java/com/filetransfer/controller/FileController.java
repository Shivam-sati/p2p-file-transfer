package com.filetransfer.controller;

import com.filetransfer.config.AppConfig.AppProperties;
import com.filetransfer.dto.request.FileInitRequest;
import com.filetransfer.dto.response.FileInitResponse;
import com.filetransfer.dto.response.FileStatusResponse;
import com.filetransfer.service.FileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService   fileService;
    private final AppProperties props;

    /**
     * POST /api/v1/files/init
     * Client declares: filename, total size, chunk count, SHA-256.
     * We return the fileId + chunk size so it knows how to slice the file.
     */
    @PostMapping("/init")
    public ResponseEntity<FileInitResponse> initUpload(
            @Valid @RequestBody FileInitRequest req,
            HttpServletRequest httpReq) {

        var file = fileService.initUpload(req, httpReq.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(FileInitResponse.from(file, props.chunkSizeBytes()));
    }

    /**
     * GET /api/v1/files/{fileId}
     * Returns current status, progress, and metadata for any file.
     */
    @GetMapping("/{fileId}")
    public ResponseEntity<FileStatusResponse> getStatus(@PathVariable UUID fileId) {
        return ResponseEntity.ok(FileStatusResponse.from(fileService.getFile(fileId)));
    }

    /**
     * DELETE /api/v1/files/{fileId}
     * Soft-deletes the file; storage is cleaned up asynchronously.
     */
    @DeleteMapping("/{fileId}")
    public ResponseEntity<Void> deleteFile(@PathVariable UUID fileId) {
        fileService.deleteFile(fileId);
        return ResponseEntity.noContent().build();
    }
}