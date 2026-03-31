package com.filetransfer.signaling;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class SignalingService {

    private final SimpMessagingTemplate messagingTemplate;

    // roomId (fileId) → set of peerIds in the room
    private final Map<String, Set<String>> rooms = new ConcurrentHashMap<>();

    // peerId → roomId (reverse index for cleanup)
    private final Map<String, String> peerToRoom = new ConcurrentHashMap<>();

    public void joinRoom(String roomId, String peerId) {
        Set<String> peers = rooms.computeIfAbsent(
                roomId, id -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
        peers.add(peerId);
        peerToRoom.put(peerId, roomId);
        log.info("Peer {} joined room {} ({} peers)", peerId, roomId, peers.size());

        if (peers.size() == 2) {
            // Notify the first peer that both sides are ready
            String otherPeerId = peers.stream()
                    .filter(p -> !p.equals(peerId))
                    .findFirst().orElse(null);

            if (otherPeerId != null) {
                SignalingMessage ready = new SignalingMessage(
                        "peer-ready", roomId, peerId, otherPeerId,
                        "{\"message\":\"Both peers connected. Sender should initiate offer.\"}");
                routeToTopic(otherPeerId, ready);
            }
        }
    }

    public void routeMessage(SignalingMessage message) {
        String target = message.getToPeerId();
        if (target == null || target.isBlank()) {
            broadcastToRoom(message.getRoomId(), message);
            return;
        }
        String senderRoom = peerToRoom.get(message.getFromPeerId());
        String receiverRoom = peerToRoom.get(target);

        if (senderRoom == null || !senderRoom.equals(message.getRoomId())) {
            sendError(message.getFromPeerId(), message.getRoomId(), "You are not in this room");
            return;
        }
        if (receiverRoom == null || !receiverRoom.equals(message.getRoomId())) {
            sendError(message.getFromPeerId(), message.getRoomId(), "Target peer not found");
            return;
        }
        routeToTopic(target, message);
    }

    public void leavePeer(String peerId) {
        String roomId = peerToRoom.remove(peerId);
        if (roomId == null)
            return;
        Set<String> peers = rooms.get(roomId);
        if (peers != null) {
            peers.remove(peerId);
            peers.forEach(remaining -> routeToTopic(remaining, SignalingMessage.leave(roomId, peerId)));
            if (peers.isEmpty()) {
                rooms.remove(roomId);
                log.info("Room {} closed", roomId);
            }
        }
    }

    public int getPeerCount(String roomId) {
        Set<String> peers = rooms.get(roomId);
        return peers == null ? 0 : peers.size();
    }

    private void routeToTopic(String peerId, SignalingMessage message) {
        messagingTemplate.convertAndSend("/topic/peer/" + peerId, message);
    }

    private void broadcastToRoom(String roomId, SignalingMessage message) {
        rooms.getOrDefault(roomId, Collections.emptySet())
                .forEach(peerId -> routeToTopic(peerId, message));
    }

    private void sendError(String toPeerId, String roomId, String reason) {
        routeToTopic(toPeerId, SignalingMessage.error(roomId, toPeerId, reason));
    }
}