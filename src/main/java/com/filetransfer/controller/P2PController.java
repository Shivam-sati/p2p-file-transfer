package com.filetransfer.controller;

import com.filetransfer.signaling.P2PSessionService;
import com.filetransfer.signaling.SignalingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/p2p")
@RequiredArgsConstructor
public class P2PController {

    private final SignalingService signalingService;
    private final P2PSessionService p2pSessionService;

    /** Check if a sender peer is waiting — call before attempting P2P */
    @GetMapping("/{fileId}/status")
    public ResponseEntity<Map<String, Object>> getPeerStatus(@PathVariable UUID fileId) {
        int peerCount = signalingService.getPeerCount(fileId.toString());
        return ResponseEntity.ok(Map.of(
                "p2pAvailable", peerCount > 0,
                "peerCount", peerCount,
                "fileId", fileId));
    }

    @PostMapping("/{fileId}/complete")
    public ResponseEntity<Void> markComplete(
            @PathVariable UUID fileId,
            @RequestParam String peerId,
            @RequestParam long bytesTransferred) {
        p2pSessionService.markCompleted(fileId.toString(), peerId, bytesTransferred);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{fileId}/fallback")
    public ResponseEntity<Void> markFallback(
            @PathVariable UUID fileId,
            @RequestParam String peerId) {
        p2pSessionService.markFallback(fileId.toString(), peerId);
        return ResponseEntity.ok().build();
    }
}