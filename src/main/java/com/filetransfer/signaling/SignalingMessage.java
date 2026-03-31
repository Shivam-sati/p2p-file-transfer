package com.filetransfer.signaling;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Single DTO for every WebRTC signaling message.
 *
 * type values:
 * "join" - peer registers with a room
 * "offer" - SDP offer from sender
 * "answer" - SDP answer from receiver
 * "ice" - ICE candidate from either peer
 * "leave" - peer disconnected or done
 * "fallback" - peer falling back to server
 * "error" - signaling error
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SignalingMessage {

    private String type;
    private String roomId; // fileId — groups sender + receiver
    private String fromPeerId;
    private String toPeerId; // null = broadcast to room
    private String payload; // SDP JSON, ICE JSON, or metadata

    public static SignalingMessage error(String roomId, String toPeerId, String reason) {
        return new SignalingMessage("error", roomId, "server", toPeerId, reason);
    }

    public static SignalingMessage leave(String roomId, String fromPeerId) {
        return new SignalingMessage("leave", roomId, fromPeerId, null, null);
    }
}