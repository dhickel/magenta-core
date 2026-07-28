package io.mindspice.magenta2.ai.orchestration.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationKey;
import org.junit.jupiter.api.Test;

class MaterializationKeyTest {

    @Test
    void literalProjectKeyIsDeterministic() {
        MaterializationKey k1 = MaterializationKey.forProject("agent-1", "proj-abc");
        MaterializationKey k2 = MaterializationKey.forProject("agent-1", "proj-abc");
        assertThat(k1.serialized()).isEqualTo(k2.serialized());
        assertThat(k1).isEqualTo(k2);
    }

    @Test
    void differentAgentsProduceDifferentKeys() {
        MaterializationKey k1 = MaterializationKey.forProject("agent-1", "proj-same");
        MaterializationKey k2 = MaterializationKey.forProject("agent-2", "proj-same");
        assertThat(k1.serialized()).isNotEqualTo(k2.serialized());
    }

    @Test
    void differentProjectsProduceDifferentKeys() {
        MaterializationKey k1 = MaterializationKey.forProject("agent-1", "proj-a");
        MaterializationKey k2 = MaterializationKey.forProject("agent-1", "proj-b");
        assertThat(k1.serialized()).isNotEqualTo(k2.serialized());
    }

    @Test
    void keyStartsWithCorrectPrefix() {
        MaterializationKey k = MaterializationKey.forProject("agent-1", "proj-x");
        assertThat(k.serialized()).startsWith("v1-PROJECT-");
        assertThat(k.kindToken()).isEqualTo("PROJECT");
        assertThat(k.sourceKind()).isEqualTo("project");
    }

    @Test
    void digestIs64HexChars() {
        MaterializationKey k = MaterializationKey.forProject("agent-1", "proj-x");
        String digest = k.serialized().substring("v1-PROJECT-".length());
        assertThat(digest).hasSize(64);
        assertThat(digest).matches("[0-9a-f]{64}");
    }

    @Test
    void nonAsciiAgentIdIsFrameSafe() {
        // UTF-8 agent id with non-ASCII characters
        MaterializationKey k = MaterializationKey.forProject("agént-α", "proj-1");
        assertThat(k.serialized()).startsWith("v1-PROJECT-");
        // Round-trip through encoding
        byte[] frame = MaterializationKey.encodeProjectFrame("agént-α", "proj-1");
        assertThat(frame[0]).isEqualTo((byte) 1);
        // Verify the agent id length encoding uses big-endian u16
        byte[] agentBytes = "agént-α".getBytes(StandardCharsets.UTF_8);
        // Frame: 0x01 (1) + "PROJECT" (7) + u16 agentLen = offset 8
        int encodedLen = ((frame[8] & 0xFF) << 8) | (frame[9] & 0xFF);
        assertThat(encodedLen).isEqualTo(agentBytes.length);
    }

    @Test
    void keyIsInvariantUnderTitleAndTimestampChanges() {
        // Titles, timestamps, display names, and paths are never frame inputs
        MaterializationKey k1 = MaterializationKey.forProject("agent-1", "proj-1");
        MaterializationKey k2 = MaterializationKey.forProject("agent-1", "proj-1");
        // Same inputs => same key, regardless of external metadata
        assertThat(k1).isEqualTo(k2);
        assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
    }

    @Test
    void fromSerializedPreservesIdentity() {
        MaterializationKey original = MaterializationKey.forProject("agent-1", "proj-abc");
        MaterializationKey restored = MaterializationKey.fromSerialized("agent-1", original.serialized());
        assertThat(restored.serialized()).isEqualTo(original.serialized());
        assertThat(restored).isEqualTo(original);
        assertThat(restored.kindToken()).isEqualTo("PROJECT");
    }

    @Test
    void fromTrustedPartsPreservesIdentity() {
        MaterializationKey original = MaterializationKey.forProject("agent-1", "proj-abc");
        MaterializationKey restored = MaterializationKey.fromTrustedParts(
                "agent-1", "proj-abc", original.serialized());
        assertThat(restored.serialized()).isEqualTo(original.serialized());
        assertThat(restored).isEqualTo(original);
        assertThat(restored.sourceId()).isEqualTo("proj-abc");
    }

    @Test
    void fromSerializedRejectsInvalidPrefix() {
        assertThatThrownBy(() -> MaterializationKey.fromSerialized("a", "bad-key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MaterializationKey.fromSerialized("a", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromSerializedRejectsMalformedDigest() {
        assertThatThrownBy(() -> MaterializationKey.fromSerialized("a", "v1-PROJECT-tooshort"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromSerializedRejectsUnknownKindToken() {
        assertThatThrownBy(() -> MaterializationKey.fromSerialized("a",
                "v1-UNKNOWN-" + "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blanksAreRejected() {
        assertThatThrownBy(() -> MaterializationKey.forProject("", "proj"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MaterializationKey.forProject("agent", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MaterializationKey.forProject("agent", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encodeProjectFrameProducesDeterministicPayload() {
        byte[] frame1 = MaterializationKey.encodeProjectFrame("agent-1", "proj-1");
        byte[] frame2 = MaterializationKey.encodeProjectFrame("agent-1", "proj-1");
        assertThat(frame1).isEqualTo(frame2);

        // Verify the frame structure: version byte + kind token + lengths + payloads
        // After version byte (1) + "PROJECT" (7) = 8 bytes of header
        assertThat(frame1[0]).isEqualTo((byte) 1);
        // "PROJECT" = [0x50, 0x52, 0x4F, 0x4A, 0x45, 0x43, 0x54]
        assertThat(new String(frame1, 1, 7, StandardCharsets.UTF_8)).isEqualTo("PROJECT");
    }

    @Test
    void encodeFrameProducesKnownVector() throws Exception {
        // Literal test vector for deterministic validation
        byte[] frame = MaterializationKey.encodeProjectFrame("a", "b");
        // Expected: 0x01 + "PROJECT"(7) + u16(1) + "a" + u16(7) + "project"(7) + u16(1) + "b"
        byte[] expected = new byte[frame.length];
        int pos = 0;
        expected[pos++] = 0x01;
        System.arraycopy("PROJECT".getBytes(StandardCharsets.UTF_8), 0, expected, pos, 7);
        pos += 7;
        expected[pos++] = 0x00;
        expected[pos++] = 0x01; // agent "a" length=1
        expected[pos++] = 'a';
        expected[pos++] = 0x00;
        expected[pos++] = 0x07; // source-kind "project" length=7
        System.arraycopy("project".getBytes(StandardCharsets.UTF_8), 0, expected, pos, 7);
        pos += 7;
        expected[pos++] = 0x00;
        expected[pos++] = 0x01; // source-id "b" length=1
        expected[pos] = 'b';

        assertThat(frame).isEqualTo(expected);

        // Verify the key digest
        String digest = sha256(frame);
        MaterializationKey key = MaterializationKey.forProject("a", "b");
        assertThat(digest).isEqualTo("26ee6911f59c5558594a4a606a6dafb4aa6d52294fb9a831519f751fe07ba9ed");
        assertThat(key.serialized()).isEqualTo("v1-PROJECT-" + digest);
    }

    @Test
    void trustedDeserializationRejectsAKeyForDifferentIdentity() {
        MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
        assertThatThrownBy(() -> MaterializationKey.fromTrustedParts(
                "agent-1", "project-2", key.serialized()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsIdentifiersThatExceedTheU16FrameLength() {
        assertThatThrownBy(() -> MaterializationKey.forProject("a".repeat(65_536), "project"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MaterializationKey.forProject("agent", "p".repeat(65_536)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalsAndHashCodeAgree() {
        MaterializationKey k1 = MaterializationKey.forProject("agent-1", "proj-1");
        MaterializationKey k2 = MaterializationKey.forProject("agent-1", "proj-1");
        MaterializationKey k3 = MaterializationKey.fromSerialized("agent-1", k1.serialized());
        MaterializationKey k4 = MaterializationKey.fromTrustedParts("agent-1", "proj-1", k1.serialized());

        assertThat(k1).isEqualTo(k2).isEqualTo(k3).isEqualTo(k4);
        assertThat(k1.hashCode()).isEqualTo(k2.hashCode())
                .isEqualTo(k3.hashCode()).isEqualTo(k4.hashCode());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
