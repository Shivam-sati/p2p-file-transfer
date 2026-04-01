/**
 * progress-tracker.js
 * Subscribes to /topic/progress/{fileId} and updates UI elements.
 *
 * Usage:
 *   const tracker = new ProgressTracker(fileId, {
 *     onProgress: (event) => updateProgressBar(event.percentComplete),
 *     onComplete: (event) => showSuccessMessage(),
 *     onFailed:   (event) => showErrorMessage(event.message)
 *   });
 *   tracker.start();
 *   // later:
 *   tracker.stop();
 */
class ProgressTracker {

    constructor(fileId, { onProgress, onComplete, onFailed } = {}) {
        this.fileId = fileId;
        this.onProgress = onProgress || (() => { });
        this.onComplete = onComplete || (() => { });
        this.onFailed = onFailed || (() => { });
        this.stompClient = null;
        this.subscription = null;
    }

    async start() {
        const socket = new SockJS('/ws/signaling');
        this.stompClient = Stomp.over(socket);
        this.stompClient.debug = () => { };

        await new Promise((resolve, reject) => {
            this.stompClient.connect({}, () => {
                this.subscription = this.stompClient.subscribe(
                    `/topic/progress/${this.fileId}`,
                    (frame) => this._handleEvent(JSON.parse(frame.body))
                );
                resolve();
            }, reject);
        });
    }

    stop() {
        this.subscription?.unsubscribe();
        this.stompClient?.deactivate();
    }

    _handleEvent(event) {
        switch (event.status) {
            case 'ACTIVE':
            case 'MERGING':
                this.onProgress(event);
                break;
            case 'COMPLETE':
                this.onComplete(event);
                this.stop();
                break;
            case 'FAILED':
                this.onFailed(event);
                this.stop();
                break;
        }
    }

    /** Utility: format bytes/s into human-readable string */
    static formatSpeed(bps) {
        if (bps >= 1_000_000) return (bps / 1_000_000).toFixed(1) + ' MB/s';
        if (bps >= 1_000) return (bps / 1_000).toFixed(1) + ' KB/s';
        return bps.toFixed(0) + ' B/s';
    }

    /** Utility: format seconds into mm:ss */
    static formatEta(seconds) {
        if (seconds < 0) return '--:--';
        const m = Math.floor(seconds / 60);
        const s = Math.floor(seconds % 60);
        return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
    }
}

if (typeof module !== 'undefined') module.exports = { ProgressTracker };
else window.ProgressTracker = ProgressTracker;