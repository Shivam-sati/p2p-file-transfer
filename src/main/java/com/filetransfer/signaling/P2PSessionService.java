package com.filetransfer.signaling;

import com.filetransfer.entity.TransferSessionEntity;
import com.filetransfer.repository.FileRepository;
import com.filetransfer.repository.TransferSessionRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class P2PSessionService {

    private final TransferSessionRepository sessionRepository;
    private final FileRepository fileRepository;

    @Transactional
    public TransferSessionEntity registerPeer(String roomId, String peerId) {
        try {
            UUID fileId = UUID.fromString(roomId);
            return fileRepository.findById(fileId).map(file -> {
                TransferSessionEntity session = new TransferSessionEntity();
                session.setFile(file);
                session.setSessionType(TransferSessionEntity.Type.P2P);
                session.setDirection(TransferSessionEntity.Direction.DOWNLOAD);
                session.setStatus(TransferSessionEntity.Status.ACTIVE);
                session.setPeerId(peerId);
                session.setStartedAt(Instant.now());
                TransferSessionEntity saved = sessionRepository.save(session);
                log.info("P2P session created: {} for peer {}", saved.getId(), peerId);
                return saved;
            }).orElse(null);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid roomId: {}", roomId);
            return null;
        }
    }

    @Transactional
    public void markFallback(String roomId, String peerId) {
        try {
            sessionRepository.findByFileIdAndPeerIdAndStatus(
                    UUID.fromString(roomId), peerId, TransferSessionEntity.Status.ACTIVE)
                    .ifPresent(session -> {
                        session.setStatus(TransferSessionEntity.Status.PEER_FALLBACK);
                        session.setCompletedAt(Instant.now());
                        sessionRepository.save(session);
                        log.info("P2P fell back: sessionId={}", session.getId());
                    });
        } catch (IllegalArgumentException e) {
            log.warn("markFallback: invalid roomId {}", roomId);
        }
    }

    @Transactional
    public void markCompleted(String roomId, String peerId, long bytesTransferred) {
        try {
            sessionRepository.findByFileIdAndPeerIdAndStatus(
                    UUID.fromString(roomId), peerId, TransferSessionEntity.Status.ACTIVE)
                    .ifPresent(session -> {
                        session.setStatus(TransferSessionEntity.Status.COMPLETED);
                        session.setBytesTransferred(bytesTransferred);
                        session.setCompletedAt(Instant.now());
                        long ms = Instant.now().toEpochMilli() - session.getStartedAt().toEpochMilli();
                        if (ms > 0)
                            session.setAvgSpeedBps(bytesTransferred * 1000.0 / ms);
                        sessionRepository.save(session);
                    });
        } catch (IllegalArgumentException e) {
            log.warn("markCompleted: invalid roomId {}", roomId);
        }
    }
}