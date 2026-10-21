// Adapted from heavendoorx7/OP-CarPlay, commit 5ea1cf60024210f3e0729ba14cda5c339be6f20b.
// GPL-3.0; XPlay adaptation 2026-10-21. See docs/P7_INSTRUMENT_LYRICS.md.
package com.shilapi.xcertplay;

/** One narrowly scoped native map-image producer, never the main navigation service. */
public final class P7MiniMapLease {
    public interface Host {
        boolean running() throws Exception;
        void remember() throws Exception;
        void stop() throws Exception;
        void start() throws Exception;
        void forget() throws Exception;
    }
    private final Host host;
    private boolean restoreNeeded;
    public P7MiniMapLease(Host host, boolean pendingRecovery) {
        this.host=host;restoreNeeded=pendingRecovery;
    }
    public synchronized boolean acquire() throws Exception {
        if(host.running()) {
            host.remember();restoreNeeded=true; // Persist recovery before a possible mutation.
            host.stop();
        }
        return !host.running();
    }
    public synchronized boolean release() throws Exception {
        if(!restoreNeeded)return true;
        host.start();
        if(!host.running())return false;
        host.forget();restoreNeeded=false;return true;
    }
}

