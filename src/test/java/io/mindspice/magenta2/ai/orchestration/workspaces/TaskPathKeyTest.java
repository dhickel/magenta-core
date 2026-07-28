package io.mindspice.magenta2.ai.orchestration.workspaces;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

class TaskPathKeyTest {
    @Test
    void fixedAsciiVectorsLockEverySourceKind() {
        assertVector(TaskSourceKind.TASK, "task-1", "015400067461736b2d31",
                "v1-task-f1e03c02cc4ab01fbbf86ac2531ab648f858dd0bbefadbf291ba67497c2ae38a");
        assertVector(TaskSourceKind.WORKFLOW, "wf-42", "0157000577662d3432",
                "v1-workflow-15e5f62f500a95c17245184c0f934706da2f51abdb72b21b3080f2d0f51b0de1");
        assertVector(TaskSourceKind.JOB, "job-99", "014a00066a6f622d3939",
                "v1-job-7bbab4d4fb9a6237dffa314d5a4a770e96d7f8b971e257d8d64278804cbeb53d");
    }

    @Test
    void fixedUnicodeAndDelimiterVectorsUseExactUtf8WithoutNormalization() {
        assertVector(TaskSourceKind.TASK, "tâche-é", "0154000974c3a26368652dc3a9",
                "v1-task-5b918ea8c8179d7cadb074c87c9f0a965594a755bc1f527c8b09f80ca411e6fa");
        assertVector(TaskSourceKind.TASK, "a:b|c", "01540005613a627c63",
                "v1-task-5e9efe736df80a3cd37ce1b63b572c3bb03d7abc61709ec24132e00e2e9050f6");
        assertNotEquals(key("caf\u00e9"), key("cafe\u0301"));
        assertNotEquals(key("ABC"), key("abc"));
        assertNotEquals(key("id"), TaskPathKey.from(new TaskIdentity(TaskSourceKind.JOB, "id")));
    }

    @Test
    void identityAcceptsExactWhitespaceButRejectsBlankAndExcessiveUtf8() {
        assertNotEquals(key("id"), key(" id "));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskIdentity(TaskSourceKind.TASK, " "));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskIdentity(TaskSourceKind.TASK, "é".repeat(513)));
        TaskPathKey.from(new TaskIdentity(TaskSourceKind.TASK, "x".repeat(1024)));
    }

    @Test
    void sourceTokensAreClosedAndStrict() {
        for (TaskSourceKind kind : TaskSourceKind.values()) {
            assertEquals(kind, TaskSourceKind.fromToken(kind.token()));
        }
        assertThrows(IllegalArgumentException.class, () -> TaskSourceKind.fromToken(" task "));
        assertThrows(IllegalArgumentException.class, () -> TaskSourceKind.fromToken("other"));
    }

    private static TaskPathKey key(String id) {
        return TaskPathKey.from(new TaskIdentity(TaskSourceKind.TASK, id));
    }

    private static void assertVector(TaskSourceKind kind, String id,
                                     String payloadHex, String serialized) {
        TaskIdentity identity = new TaskIdentity(kind, id);
        assertArrayEquals(HexFormat.of().parseHex(payloadHex),
                TaskPathKey.encodePayload(kind, id.getBytes(StandardCharsets.UTF_8)));
        assertEquals(serialized, TaskPathKey.from(identity).serialized());
    }
}
