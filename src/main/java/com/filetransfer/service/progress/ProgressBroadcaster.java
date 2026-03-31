package com.filetransfer.service.progress;

import com.filetransfer.entity.FileEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Broadcasts transfer progress events over WebSocket.
 *
 * Clients subscribe to /topic/progress/{fileId} to receive live updates.
 * Each update is a ProgressEvent JSON object.
 *
 * SpeedCalculator instances are kept per fileId in a ConcurrentHashMap
 * and removed when the transfer completes or fails.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProgressBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    // One SpeedCalculator per active transfer
    private final Map<UUID, SpeedCalculator> calculators = new ConcurrentHashMap<>();

    // ── Upload events ─────────────────────────────────────────────────────────

    /**
     * Called by ChunkController after each successful chunk upload.
     */
    public void chunkUploaded(FileEntity file, long chunkBytes) {
        SpeedCalculator calc = calculators.computeIfAbsent(file.getId(), id -> new SpeedCalculator());
        calc.record(chunkBytes);

        long transferred = (long) file.getUploadedChunks() * chunkBytes;
        long remaining = file.getFileSize() - transferred;

        ProgressEvent event = ProgressEvent.builder()
                .fileId(file.getId())
                .direction(ProgressEvent.Direction.UPLOAD)
                .status(ProgressEvent.Status.ACTIVE)
                .bytesTransferred(transferred)
                .totalBytes(file.getFileSize())
                .percentComplete(percent(file.getUploadedChunks(), file.getTotalChunks()))
                .speedBps(calc.getSpeedBps())
                .etaSeconds(calc.getEtaSeconds(remaining))
                .chunksUploaded(file.getUploadedChunks())
                .totalChunks(file.getTotalChunks())
                .message("Uploading chunk " + file.getUploadedChunks() + "/" + file.getTotalChunks())
                .build();

        broadcast(file.getId(), event);
    }

    public void uploadMerging(UUID fileId) {
        broadcast(fileId, ProgressEvent.builder()
                .fileId(fileId)
                .direction(ProgressEvent.Direction.UPLOAD)
                .status(ProgressEvent.Status.MERGING)
                .percentComplete(99.0)
                .message("Assembling file…")
                .build());
    }

    public void uploadComplete(UUID fileId, long totalBytes) {
        calculators.remove(fileId);
        broadcast(fileId, ProgressEvent.builder()
                .fileId(fileId)
                .direction(ProgressEvent.Direction.UPLOAD)
                .status(ProgressEvent.Status.COMPLETE)
                .bytesTransferred(totalBytes)
                .totalBytes(totalBytes)
                .percentComplete(100.0)
                .speedBps(0)
                .etaSeconds(0)
                .message("Upload complete")
                .build());
    }

    public void uploadFailed(UUID fileId, String reason) {
        calculators.remove(fileId);
        broadcast(fileId, ProgressEvent.builder()
                .fileId(fileId)
                .direction(ProgressEvent.Direction.UPLOAD)
                .status(ProgressEvent.Status.FAILED)
                .message("Upload failed: " + reason)
                .build());
    }

    // ── Download events ───────────────────────────────────────────────────────

    /**
     * Called by ShareService on each buffer flush during streaming.
     * 
     * @param bytesJustSent bytes sent in this flush (for speed calc)
     * @param totalSent     cumulative bytes sent so far
     * @param totalBytes    total file size
     */
    public void downloadProgress(UUID fileId, long bytesJustSent, long totalSent, long totalBytes) {
        SpeedCalculator calc = calculators.computeIfAbsent(fileId, id -> new SpeedCalculator());
        calc.record(bytesJustSent);

        broadcast(fileId, ProgressEvent.builder()
                .fileId(fileId)
                .direction(ProgressEvent.Direction.DOWNLOAD)
                .status(ProgressEvent.Status.ACTIVE)
                .bytesTransferred(totalSent)
                .totalBytes(totalBytes)
                .percentComplete(percent(totalSent, totalBytes))
                .speedBps(calc.getSpeedBps())
                .etaSeconds(calc.getEtaSeconds(totalBytes - totalSent))
                .message("Downloading…")
                .build());
    }

    public void downloadComplete(UUID fileId, long totalBytes) {
        calculators.remove(fileId);
        broadcast(fileId, ProgressEvent.builder()
                .fileId(fileId)
                .direction(ProgressEvent.Direction.DOWNLOAD)
                .status(ProgressEvent.Status.COMPLETE)
                .bytesTransferred(totalBytes)
                .totalBytes(totalBytes)
                .percentComplete(100.0)
                .speedBps(0)
                .etaSeconds(0)
                .message("Download complete")
                .build());
    }

    public void downloadFailed(UUID fileId, String reason) {
        calculators.remove(fileId);
        broadcast(fileId, ProgressEvent.builder()
                .fileId(fileId)
                .direction(ProgressEvent.Direction.DOWNLOAD)
                .status(ProgressEvent.Status.FAILED)
                .message("Download failed: " + reason)
                .build());
    }

    // ── private ───────────────────────────────────────────────────────────────

    private void broadcast(UUID fileId, ProgressEvent event) {
        messagingTemplate.convertAndSend("/topic/progress/" + fileId, event);
        log.debug("Progress: fileId={} {}% dir={} speed={:.0f}bps",
                fileId, String.format("%.1f", event.getPercentComplete()),
                event.getDirection(), event.getSpeedBps());
    }

    private double percent(long done, long total) {
        return total <= 0 ? 0.0 : Math.min(100.0, done * 100.0 / total);
    }
}