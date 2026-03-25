package com.filetransfer.dto.response;
 
import com.filetransfer.entity.FileEntity;
import com.filetransfer.entity.ShareCodeEntity;
 
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ShareInfoResponse(
    String  code,
    String  shareUrl,
    String  fileName,
    long    fileSize,
    String  mimeType,
    boolean passwordProtected,
    Integer maxDownloads,
    int     downloadCount,
    Instant expiresAt
) {
    public static ShareInfoResponse from(ShareCodeEntity share, String baseUrl) {
        var file = share.getFile();
        return new ShareInfoResponse(
            share.getCode(),
            baseUrl + "/api/v1/share/" + share.getCode() + "/download",
            file.getOriginalName(),
            file.getFileSize(),
            file.getMimeType(),
            share.isPasswordProtected(),
            share.getMaxDownloads(),
            share.getDownloadCount(),
            share.getExpiresAt()
        );
    }
}