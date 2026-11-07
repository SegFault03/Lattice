package com.segfault03.ideadb.model;

import java.util.Locale;

/** Display-only stand-in for an oversized LOB: the preview is never the real value, so it must not be edited, exported or written back. */
public record TruncatedValue(Object preview, long fullLength) {
    public TruncatedValue {
        if (!(preview instanceof byte[] || preview instanceof String)) throw new IllegalArgumentException("Preview must be bytes or text");
    }
    public boolean binary() { return preview instanceof byte[]; }
    /** Full size as shown to the user, e.g. {@code 3.1 MiB} or {@code 1.5 Mi chars}. */
    public String sizeLabel() {
        return String.format(Locale.ROOT, "%.1f %s", fullLength / (1024.0 * 1024.0), binary() ? "MiB" : "Mi chars");
    }
    public String suffix() { return " … (truncated, " + sizeLabel() + ")"; }
    /** Clipboard and default-renderer text: the preview content followed by the truncation notice. */
    @Override public String toString() {
        String text = preview instanceof byte[] bytes ? "0x" + java.util.HexFormat.of().withUpperCase().formatHex(bytes) : (String) preview;
        return text + suffix();
    }
    @Override public boolean equals(Object other) {
        return other instanceof TruncatedValue value && fullLength == value.fullLength && java.util.Objects.deepEquals(preview, value.preview);
    }
    @Override public int hashCode() { return 31 * Long.hashCode(fullLength) + (preview instanceof byte[] bytes ? java.util.Arrays.hashCode(bytes) : preview.hashCode()); }
}
