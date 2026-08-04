package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfinedPathMutationServiceTest {

    @TempDir Path tempDir;

    @Test
    void heldParentHandleDefeatsCoordinatedParentSymlinkSwap() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        Path parent = Files.createDirectory(root.resolve("notes"));
        Files.writeString(parent.resolve("a.txt"), "before");
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("a.txt"), "protected");
        Path heldParent = root.resolve("notes-held");
        CountDownLatch parentOpened = new CountDownLatch(1);
        CountDownLatch swapped = new CountDownLatch(1);
        ConfinedPathMutationService service = new ConfinedPathMutationService((primitive, ignored, relative) -> {
            if (primitive == ConfinedPathMutationService.Primitive.WRITE) {
                parentOpened.countDown();
                awaitSwap(swapped);
            }
        });

        FutureTask<Void> mutation = new FutureTask<>(() -> {
            service.write(root, "notes/a.txt", "changed".getBytes(StandardCharsets.UTF_8),
                ConfinedPathMutationService.WriteMode.OVERWRITE, false);
            return null;
        });
        Thread worker = Thread.ofPlatform().start(mutation);
        assertThat(parentOpened.await(5, TimeUnit.SECONDS)).isTrue();
        Files.move(parent, heldParent);
        Files.createSymbolicLink(root.resolve("notes"), outside);
        swapped.countDown();
        mutation.get(5, TimeUnit.SECONDS);
        worker.join();

        assertThat(Files.readString(outside.resolve("a.txt"))).isEqualTo("protected");
        assertThat(Files.readString(heldParent.resolve("a.txt"))).isEqualTo("changed");
    }

    @Test
    void heldDestinationHandleDefeatsCoordinatedDestinationSwap() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        Files.writeString(root.resolve("source.txt"), "safe");
        Path destination = Files.createDirectory(root.resolve("destination"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path heldDestination = root.resolve("destination-held");
        CountDownLatch destinationOpened = new CountDownLatch(1);
        CountDownLatch swapped = new CountDownLatch(1);
        ConfinedPathMutationService service = new ConfinedPathMutationService((primitive, ignored, relative) -> {
            if (primitive == ConfinedPathMutationService.Primitive.MOVE) {
                destinationOpened.countDown();
                awaitSwap(swapped);
            }
        });

        FutureTask<Void> mutation = new FutureTask<>(() -> {
            service.move(root, "source.txt", "destination/moved.txt");
            return null;
        });
        Thread worker = Thread.ofPlatform().start(mutation);
        assertThat(destinationOpened.await(5, TimeUnit.SECONDS)).isTrue();
        Files.move(destination, heldDestination);
        Files.createSymbolicLink(root.resolve("destination"), outside);
        swapped.countDown();
        mutation.get(5, TimeUnit.SECONDS);
        worker.join();

        assertThat(outside.resolve("moved.txt")).doesNotExist();
        assertThat(heldDestination.resolve("moved.txt")).hasContent("safe");
    }

    @Test
    void multiLinkOrdinaryAliasCannotMutateProtectedBytes() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        Path outputs = Files.createDirectory(root.resolve("outputs"));
        Path protectedFile = Files.writeString(outputs.resolve("result.txt"), "protected");
        Path alias = root.resolve("ordinary.txt");
        Files.createLink(alias, protectedFile);
        ConfinedPathMutationService service = new ConfinedPathMutationService();

        assertThatThrownBy(() -> service.write(root, "ordinary.txt",
                "changed".getBytes(StandardCharsets.UTF_8),
                ConfinedPathMutationService.WriteMode.OVERWRITE, false))
            .isInstanceOf(ConfinedPathMutationService.HardLinkDeniedException.class)
            .hasMessageContaining("multi-link");
        assertThat(protectedFile).hasContent("protected");
        assertThat(alias).hasContent("protected");
    }

    private static void awaitSwap(CountDownLatch latch) throws java.io.IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("swap did not complete");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("swap interrupted", exception);
        }
    }

    @Test
    void nonDefaultProviderFailsClosedWithoutPathFallback() throws Exception {
        Path archive = tempDir.resolve("provider.zip");
        URI uri = URI.create("jar:" + archive.toUri());
        try (FileSystem zip = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path root = Files.createDirectory(zip.getPath("/root"));
            ConfinedPathMutationService service = new ConfinedPathMutationService();
            assertThat(service.supported(root)).isFalse();
            assertThatThrownBy(() -> service.write(root, "file.txt", new byte[0],
                    ConfinedPathMutationService.WriteMode.CREATE_NEW, false))
                .isInstanceOf(ConfinedPathMutationService.ConfinementUnavailableException.class)
                .hasMessageContaining("unavailable");
            assertThat(root.resolve("file.txt")).doesNotExist();
        }
    }
}
