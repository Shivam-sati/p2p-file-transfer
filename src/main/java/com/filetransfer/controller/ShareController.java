package com.filetransfer.controller;

import com.filetransfer.dto.request.ShareCreateRequest;
import com.filetransfer.dto.response.ShareInfoResponse;
import com.filetransfer.service.ShareService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;

    /**
     * POST /api/v1/files/{fileId}/share
     * Generate a shareable code for a READY file.
     */
    @PostMapping("/api/v1/files/{fileId}/share")
    public ResponseEntity<ShareInfoResponse> createShare(
            @PathVariable UUID fileId,
            @Valid @RequestBody(required = false) ShareCreateRequest req,
            HttpServletRequest httpReq) {

        var share = shareService.createShareCode(fileId, req);
        String baseUrl = httpReq.getScheme() + "://" + httpReq.getServerName()
            + (httpReq.getServerPort() != 80 && httpReq.getServerPort() != 443
               ? ":" + httpReq.getServerPort() : "");

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ShareInfoResponse.from(share, baseUrl));
    }

    /**
     * GET /api/v1/share/{code}
     * Resolve a code to file metadata — shown before the download starts.
     */
    @GetMapping("/api/v1/share/{code}")
    public ResponseEntity<ShareInfoResponse> getShareInfo(
            @PathVariable String code,
            HttpServletRequest httpReq) {

        var share = shareService.resolveCode(code);
        String baseUrl = httpReq.getScheme() + "://" + httpReq.getServerName()
            + (httpReq.getServerPort() != 80 && httpReq.getServerPort() != 443
               ? ":" + httpReq.getServerPort() : "");

        return ResponseEntity.ok(ShareInfoResponse.from(share, baseUrl));
    }

    /**
     * GET /api/v1/share/{code}/download
     * Streams the file. Supports Range header for resumable downloads.
     * Response is binary — no JSON wrapper.
     */
    @GetMapping("/api/v1/share/{code}/download")
    public void downloadFile(
            @PathVariable String code,
            HttpServletRequest httpReq,
            HttpServletResponse httpRes) throws IOException {

        shareService.streamFile(code, httpReq, httpRes);
    }
}