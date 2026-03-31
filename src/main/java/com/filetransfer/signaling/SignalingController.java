package com.filetransfer.signaling;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Controller
@RequiredArgsConstructor
@Slf4j
public class SignalingController {

    private final SignalingService signalingService;
    private final P2PSessionService p2pSessionService;

    /**
     * All WebRTC signaling messages arrive here.
     * Client sends to /app/signal
     */
    @MessageMapping("/signal")
    public void handleSignal(@Payload SignalingMessage message) {
        log.debug("Signal: type={} from={} room={}",
                message.getType(), message.getFromPeerId(), message.getRoomId());

        switch (message.getType()) {
            case "join" -> handleJoin(message);
            case "offer" -> signalingService.routeMessage(message);
            case "answer" -> signalingService.routeMessage(message);
            case "ice" -> signalingService.routeMessage(message);
            case "leave" -> signalingService.leavePeer(message.getFromPeerId());
            case "fallback" -> handleFallback(message);
            default -> log.warn("Unknown signal type: {}", message.getType());
        }
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        log.info("WebSocket disconnected: {}", sessionId);
        signalingService.leavePeer(sessionId);
    }

    private void handleJoin(SignalingMessage message) {
        signalingService.joinRoom(message.getRoomId(), message.getFromPeerId());
        p2pSessionService.registerPeer(message.getRoomId(), message.getFromPeerId());
    }

    private void handleFallback(SignalingMessage message) {
        p2pSessionService.markFallback(message.getRoomId(), message.getFromPeerId());
        signalingService.routeMessage(message);
    }
}