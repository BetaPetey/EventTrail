package io.github.betapetey.eventtrail.storage;

import java.io.IOException;

/** A Linux descriptor number is consumed before close, including a failed close. */
final class LinuxOwnedDescriptor implements AutoCloseable {
    @FunctionalInterface
    interface Closer { void close(int descriptor) throws IOException; }

    private final Closer closer;
    private int descriptor;

    LinuxOwnedDescriptor(int descriptor, Closer closer) {
        if (descriptor < 0) throw new IllegalArgumentException("Invalid native descriptor");
        this.descriptor = descriptor;
        this.closer = java.util.Objects.requireNonNull(closer);
    }

    synchronized int value() throws IOException {
        if (descriptor < 0) throw new IOException("SQLite directory authority is already closed.");
        return descriptor;
    }

    @Override public synchronized void close() throws IOException {
        int consumed = descriptor;
        descriptor = -1;
        // Linux may already have reused the number after close reports EINTR or
        // another error. Retrying could close an unrelated native connection.
        if (consumed >= 0) closer.close(consumed);
    }
}
