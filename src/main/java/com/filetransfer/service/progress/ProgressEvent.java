package com.filetransfer.service.progress;

import lombok.Builder;
import lombok.Data;
import java.util.UUID;

/**
 * Broadcast over /topic/progress/{fileId} whenever transfer state changes.
 * Both upload and download use the same DTO — the "direction" field
 * tells the client which way data is flowing.
 */
@Data
@Builder
public class ProgressEvent {

    public enum Direction {
        UPLOAD, DOWNLOAD
    }

    public enum Status {
        ACTIVE, MERGING, COMPLETE, FAILED
    }

    private UUID fileId;
    private Direction direction;
    private Status status;

    private long bytesTransferred; // total bytes so far
    private long totalBytes; // declared file size
    private double percentComplete; // 0.0 – 100.0

    private double speedBps; // current rolling-average speed
    private long etaSeconds; // estimated seconds remaining (-1 = unknown)

    private int chunksUploaded; // upload only
    private int totalChunks; // upload only

    private String message; // human-readable status text
}