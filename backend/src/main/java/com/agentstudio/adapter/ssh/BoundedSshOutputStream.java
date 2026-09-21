package com.agentstudio.adapter.ssh;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class BoundedSshOutputStream extends OutputStream {
    private final byte[] first;
    private final byte[] tail;
    private int firstSize;
    private int tailSize;
    private int tailCursor;
    private long total;

    BoundedSshOutputStream(int limit) {
        this.first = new byte[limit / 2];
        this.tail = new byte[limit / 2];
    }

    @Override public synchronized void write(int value) { append((byte) value); }
    @Override public synchronized void write(byte[] values, int offset, int length) {
        for (int index = 0; index < length; index++) append(values[offset + index]);
    }

    private void append(byte value) {
        total++;
        if (firstSize < first.length) first[firstSize++] = value;
        else {
            tail[tailCursor] = value;
            tailCursor = (tailCursor + 1) % tail.length;
            if (tailSize < tail.length) tailSize++;
        }
    }

    synchronized boolean truncated() { return total > first.length + tail.length; }

    synchronized String value() {
        if (total <= first.length) return new String(first, 0, firstSize, StandardCharsets.UTF_8);
        if (!truncated()) return new String(first, 0, firstSize, StandardCharsets.UTF_8)
                + new String(tail, 0, tailSize, StandardCharsets.UTF_8);
        var orderedTail = new byte[tailSize];
        for (int index = 0; index < tailSize; index++) {
            orderedTail[index] = tail[(tailCursor + index) % tail.length];
        }
        return new String(first, 0, firstSize, StandardCharsets.UTF_8)
                + "\n... 输出已截断 ...\n" + new String(orderedTail, StandardCharsets.UTF_8);
    }
}
