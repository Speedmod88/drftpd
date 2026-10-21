package org.drftpd.archive.master;

/** Cooperative release-boundary control; stopping invalidates already submitted work. */
public final class ArchiveControl {
    private String state = "running";
    private long generation;
    public synchronized String state() { return state; }
    public synchronized long generation() { return generation; }
    public synchronized void change(String action) {
        switch (action) {
            case "pause" -> state = "paused";
            case "resume" -> state = "running";
            case "stop" -> { state = "stopped"; generation++; }
            default -> throw new IllegalArgumentException("Unknown archive control");
        }
        notifyAll();
    }
    public synchronized boolean await(long submittedGeneration) {
        try {
            while (generation == submittedGeneration && state.equals("paused")) wait();
            return generation == submittedGeneration && state.equals("running");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
