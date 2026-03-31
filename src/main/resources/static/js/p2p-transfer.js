/**
 * p2p-transfer.js
 * Handles the full WebRTC P2P file transfer lifecycle.
 *
 * Usage (sender side):
 *   const sender = new P2PTransfer({ fileId, file, onProgress, onFallback });
 *   await sender.connect();
 *
 * Usage (receiver side):
 *   const receiver = new P2PTransfer({ fileId, peerId, onProgress, onComplete });
 *   await receiver.connect();
 */

const ICE_SERVERS = [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' }
];

const CHUNK_SIZE = 64 * 1024;   // 64 KB per data channel message
const MAX_BUFFERED = 1024 * 1024; // Pause sending if buffer > 1 MB (back-pressure)
const FALLBACK_DELAY = 15_000;      // 15 s — if no P2P connection, fall back

class P2PTransfer {

    constructor({ fileId, file, peerId, onProgress, onComplete, onFallback, onError }) {
        this.fileId = fileId;
        this.file = file;           // File object (sender only)
        this.peerId = peerId || crypto.randomUUID();
        this.onProgress = onProgress || (() => { });
        this.onComplete = onComplete || (() => { });
        this.onFallback = onFallback || (() => { });
        this.onError = onError || console.error;

        this.stompClient = null;
        this.pc = null;   // RTCPeerConnection
        this.dataChannel = null;
        this.remotePeerId = null;
        this.isSender = !!file;
        this.receivedChunks = [];
        this.receivedBytes = 0;
        this.totalBytes = 0;
        this._fallbackTimer = null;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    async connect() {
        await this._connectSignaling();
        this._joinRoom();
        this._startFallbackTimer();
    }

    disconnect() {
        this._clearFallbackTimer();
        this.stompClient?.deactivate();
        this.pc?.close();
    }

    // ── Signaling ─────────────────────────────────────────────────────────────

    async _connectSignaling() {
        // SockJS + STOMP — loaded via CDN in the HTML page
        const socket = new SockJS('/ws/signaling');
        this.stompClient = Stomp.over(socket);
        this.stompClient.debug = () => { };  // silence verbose STOMP logs

        await new Promise((resolve, reject) => {
            this.stompClient.connect({}, () => {
                // Subscribe to our private topic — only messages addressed to us arrive here
                this.stompClient.subscribe(`/topic/peer/${this.peerId}`, (frame) => {
                    this._handleSignal(JSON.parse(frame.body));
                });
                resolve();
            }, reject);
        });
    }

    _joinRoom() {
        this._send({ type: 'join', roomId: this.fileId, fromPeerId: this.peerId });
    }

    _send(message) {
        this.stompClient.send('/app/signal', {}, JSON.stringify(message));
    }

    async _handleSignal(message) {
        switch (message.type) {
            case 'peer-ready':
                // Both peers in room — sender creates offer
                this.remotePeerId = message.fromPeerId;
                if (this.isSender) await this._createOffer();
                break;

            case 'offer':
                this.remotePeerId = message.fromPeerId;
                await this._handleOffer(message.payload);
                break;

            case 'answer':
                await this._handleAnswer(message.payload);
                break;

            case 'ice':
                await this._handleIce(message.payload);
                break;

            case 'fallback':
                this._activateFallback('Remote peer requested fallback');
                break;

            case 'leave':
                this.onError('Remote peer disconnected');
                break;

            case 'error':
                this.onError(message.payload);
                break;
        }
    }

    // ── WebRTC ────────────────────────────────────────────────────────────────

    _createPeerConnection() {
        this.pc = new RTCPeerConnection({ iceServers: ICE_SERVERS });

        // Forward ICE candidates to the remote peer via signaling
        this.pc.onicecandidate = ({ candidate }) => {
            if (candidate) {
                this._send({
                    type: 'ice',
                    roomId: this.fileId,
                    fromPeerId: this.peerId,
                    toPeerId: this.remotePeerId,
                    payload: JSON.stringify(candidate)
                });
            }
        };

        this.pc.onconnectionstatechange = () => {
            const state = this.pc.connectionState;
            console.log('P2P connection state:', state);
            if (state === 'connected') {
                this._clearFallbackTimer(); // P2P succeeded — cancel fallback
            } else if (state === 'failed' || state === 'disconnected') {
                this._activateFallback(`Connection ${state}`);
            }
        };
    }

    async _createOffer() {
        this._createPeerConnection();

        // Sender creates the data channel before the offer
        this.dataChannel = this.pc.createDataChannel('fileTransfer', {
            ordered: true   // TCP-like ordering — critical for file integrity
        });
        this._setupSenderDataChannel();

        const offer = await this.pc.createOffer();
        await this.pc.setLocalDescription(offer);

        this._send({
            type: 'offer',
            roomId: this.fileId,
            fromPeerId: this.peerId,
            toPeerId: this.remotePeerId,
            payload: JSON.stringify(offer)
        });
    }

    async _handleOffer(offerJson) {
        this._createPeerConnection();

        // Receiver waits for the data channel to be created by the sender
        this.pc.ondatachannel = ({ channel }) => {
            this.dataChannel = channel;
            this._setupReceiverDataChannel();
        };

        const offer = JSON.parse(offerJson);
        await this.pc.setRemoteDescription(offer);

        const answer = await this.pc.createAnswer();
        await this.pc.setLocalDescription(answer);

        this._send({
            type: 'answer',
            roomId: this.fileId,
            fromPeerId: this.peerId,
            toPeerId: this.remotePeerId,
            payload: JSON.stringify(answer)
        });
    }

    async _handleAnswer(answerJson) {
        const answer = JSON.parse(answerJson);
        await this.pc.setRemoteDescription(answer);
    }

    async _handleIce(candidateJson) {
        const candidate = JSON.parse(candidateJson);
        await this.pc.addIceCandidate(candidate);
    }

    // ── Data Channel — Sender ─────────────────────────────────────────────────

    _setupSenderDataChannel() {
        this.dataChannel.binaryType = 'arraybuffer';

        this.dataChannel.onopen = async () => {
            console.log('Data channel open — starting file transfer');
            // Send file metadata first so receiver knows total size + filename
            const meta = JSON.stringify({
                name: this.file.name,
                size: this.file.size,
                type: this.file.type
            });
            this.dataChannel.send(meta);
            await this._sendFileChunks();
        };

        this.dataChannel.onerror = (err) => {
            console.error('Data channel error:', err);
            this._activateFallback('Data channel error');
        };
    }

    async _sendFileChunks() {
        let offset = 0;
        const total = this.file.size;

        while (offset < total) {
            // Back-pressure: wait if buffer is too full
            if (this.dataChannel.bufferedAmount > MAX_BUFFERED) {
                await new Promise(resolve =>
                    this.dataChannel.onbufferedamountlow = resolve
                );
                this.dataChannel.bufferedAmountLowThreshold = MAX_BUFFERED / 2;
            }

            const slice = this.file.slice(offset, offset + CHUNK_SIZE);
            const buffer = await slice.arrayBuffer();
            this.dataChannel.send(buffer);

            offset += buffer.byteLength;
            this.onProgress({ sent: offset, total, percent: (offset / total) * 100 });
        }

        // Signal transfer complete
        this.dataChannel.send(JSON.stringify({ type: 'done' }));
        console.log('File transfer complete via P2P');

        // Notify server for analytics
        this._send({
            type: 'leave',
            roomId: this.fileId,
            fromPeerId: this.peerId
        });
    }

    // ── Data Channel — Receiver ───────────────────────────────────────────────

    _setupReceiverDataChannel() {
        this.dataChannel.binaryType = 'arraybuffer';
        let metaReceived = false;
        let fileName = 'download';
        let mimeType = 'application/octet-stream';

        this.dataChannel.onmessage = ({ data }) => {
            if (!metaReceived) {
                // First message is always the metadata JSON
                try {
                    const meta = JSON.parse(data);
                    fileName = meta.name;
                    mimeType = meta.type;
                    this.totalBytes = meta.size;
                    metaReceived = true;
                } catch {
                    // Not JSON — treat as binary directly (shouldn't happen)
                    metaReceived = true;
                }
                return;
            }

            if (typeof data === 'string') {
                // Control message — check for 'done'
                try {
                    const ctrl = JSON.parse(data);
                    if (ctrl.type === 'done') {
                        this._assembleAndDownload(fileName, mimeType);
                    }
                } catch { /* ignore */ }
                return;
            }

            // Binary chunk
            this.receivedChunks.push(data);
            this.receivedBytes += data.byteLength;
            this.onProgress({
                received: this.receivedBytes,
                total: this.totalBytes,
                percent: (this.receivedBytes / this.totalBytes) * 100
            });
        };

        this.dataChannel.onerror = () => this._activateFallback('Data channel error');
    }

    _assembleAndDownload(fileName, mimeType) {
        const blob = new Blob(this.receivedChunks, { type: mimeType });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = fileName;
        a.click();
        URL.revokeObjectURL(url);
        this.onComplete({ fileName, size: this.receivedBytes });
    }

    // ── Fallback ──────────────────────────────────────────────────────────────

    _startFallbackTimer() {
        this._fallbackTimer = setTimeout(() => {
            this._activateFallback('P2P connection timeout');
        }, FALLBACK_DELAY);
    }

    _clearFallbackTimer() {
        if (this._fallbackTimer) {
            clearTimeout(this._fallbackTimer);
            this._fallbackTimer = null;
        }
    }

    _activateFallback(reason) {
        console.warn('Falling back to server transfer:', reason);
        this._clearFallbackTimer();
        this.pc?.close();

        // Notify server (for analytics) + remote peer
        this._send({
            type: 'fallback',
            roomId: this.fileId,
            fromPeerId: this.peerId,
            toPeerId: this.remotePeerId,
            payload: reason
        });

        // Let the caller switch to the server download URL
        this.onFallback({ reason, fileId: this.fileId });
        this.disconnect();
    }
}

// Export for module usage; also expose globally for plain <script> inclusion
if (typeof module !== 'undefined') module.exports = { P2PTransfer };
else window.P2PTransfer = P2PTransfer;