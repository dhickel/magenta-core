package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Filesystem-safe digest of an immutable, canonical task identity. */
public final class TaskPathKey {
    private static final byte ENCODING_VERSION = 1;

    private final TaskIdentity identity;
    private final String serialized;

    private TaskPathKey(TaskIdentity identity, String digest) {
        this.identity = identity;
        this.serialized = "v1-" + identity.sourceKind().token() + "-" + digest;
    }

    /**
     * Canonical payload: version byte {@code 0x01}, source-kind ASCII byte,
     * two-byte unsigned big-endian UTF-8 stable-id length, then the exact UTF-8
     * stable-id bytes. Unicode normalization and case folding are not applied.
     */
    public static TaskPathKey from(TaskIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        byte[] idBytes = identity.stableId().getBytes(StandardCharsets.UTF_8);
        return new TaskPathKey(identity,
                sha256Hex(encodePayload(identity.sourceKind(), idBytes)));
    }

    public String serialized() {
        return serialized;
    }

    public TaskIdentity identity() {
        return identity;
    }

    static byte[] encodePayload(TaskSourceKind sourceKind, byte[] stableIdBytes) {
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(stableIdBytes, "stableIdBytes");
        if (stableIdBytes.length > 0xFFFF) {
            throw new IllegalArgumentException("stableId exceeds canonical two-byte length");
        }
        byte[] payload = new byte[4 + stableIdBytes.length];
        payload[0] = ENCODING_VERSION;
        payload[1] = sourceKind.canonicalByte();
        payload[2] = (byte) (stableIdBytes.length >>> 8);
        payload[3] = (byte) stableIdBytes.length;
        System.arraycopy(stableIdBytes, 0, payload, 4, stableIdBytes.length);
        return payload;
    }

    private static String sha256Hex(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof TaskPathKey that
                && serialized.equals(that.serialized);
    }

    @Override
    public int hashCode() {
        return serialized.hashCode();
    }

    @Override
    public String toString() {
        return serialized;
    }
}
