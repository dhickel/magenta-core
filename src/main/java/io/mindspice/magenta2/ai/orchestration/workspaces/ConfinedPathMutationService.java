package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Structure;
import org.springframework.stereotype.Component;

/**
 * Linux-provider mutation boundary based on {@code openat(2)} directory handles.
 *
 * <p>The JDK {@link java.nio.file.SecureDirectoryStream} API cannot create a
 * directory and cannot expose {@code st_nlink} for an opened file.  The deployed
 * Linux file provider therefore uses the equivalent native relative primitives:
 * every component is opened with {@code O_NOFOLLOW}, the parent descriptor is
 * held through the mutation, existing regular files are checked with
 * {@code fstat(2)}, and rename uses {@code RENAME_NOREPLACE}.  Non-Linux,
 * non-64-bit, and non-default-file providers fail closed; there is deliberately
 * no path-based fallback carrying a "confined" claim.
 */
@Component
public final class ConfinedPathMutationService {
    private static final int O_RDONLY = 0;
    private static final int O_WRONLY = 1;
    private static final int O_CREAT = 64;
    private static final int O_EXCL = 128;
    private static final int O_APPEND = 1024;
    private static final int O_CLOEXEC = 524288;
    private static final int O_DIRECTORY = 65536;
    private static final int O_NOFOLLOW = 131072;
    private static final int SEEK_SET = 0;
    private static final int AT_REMOVEDIR = 0x200;
    private static final int RENAME_NOREPLACE = 1;
    private static final int S_IFMT = 0170000;
    private static final int S_IFREG = 0100000;
    private static final int EEXIST = 17;
    private static final int ENOENT = 2;
    private static final int BUFFER_BYTES = 64 * 1024;

    public enum WriteMode { CREATE_NEW, OVERWRITE, APPEND }
    public enum Primitive { WRITE, CREATE_DIRECTORY, MOVE, DELETE, READ }

    @FunctionalInterface
    interface MutationHook {
        void beforePrimitive(Primitive primitive, Path root, String relativePath) throws IOException;
    }

    private static final MutationHook NO_HOOK = (primitive, root, relativePath) -> { };
    private final MutationHook hook;

    public ConfinedPathMutationService() {
        this(NO_HOOK);
    }

    ConfinedPathMutationService(MutationHook hook) {
        this.hook = Objects.requireNonNull(hook);
    }

    public boolean supported(Path root) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.contains("linux")
            && (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("aarch64"))
            && root.getFileSystem().equals(FileSystems.getDefault())
            && Files.getFileAttributeView(root, java.nio.file.attribute.PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS) != null;
    }

    public void requireSupported(Path root) {
        if (!supported(root)) {
            throw new ConfinementUnavailableException(
                "race-resistant workspace mutation is unavailable for filesystem provider "
                    + root.getFileSystem().provider().getClass().getName());
        }
    }

    public void write(Path root, String relativePath, byte[] bytes, WriteMode mode,
                      boolean createParents) throws IOException {
        Path relative = normalizedRelative(relativePath);
        requireLeaf(relative);
        requireSupported(root);
        try (ParentHandle parent = openParent(root, relative, createParents)) {
            hook.beforePrimitive(Primitive.WRITE, root, slash(relative));
            int flags = O_WRONLY | O_CLOEXEC | O_NOFOLLOW;
            if (mode == WriteMode.CREATE_NEW) flags |= O_CREAT | O_EXCL;
            if (mode == WriteMode.APPEND) flags |= O_APPEND;
            int fd = LibC.INSTANCE.openat(parent.fd(), relative.getFileName().toString(), flags, 0600);
            if (fd < 0) throw io("openat", slash(relative));
            try {
                Stat stat = fstat(fd, slash(relative));
                requireRegularSingleLink(stat, slash(relative));
                if (mode == WriteMode.OVERWRITE && LibC.INSTANCE.ftruncate(fd, 0) != 0) {
                    throw io("ftruncate", slash(relative));
                }
                if (mode != WriteMode.APPEND && LibC.INSTANCE.lseek(fd, 0, SEEK_SET) < 0) {
                    throw io("lseek", slash(relative));
                }
                writeAll(fd, bytes == null ? new byte[0] : bytes, slash(relative));
                if (LibC.INSTANCE.fsync(fd) != 0) throw io("fsync", slash(relative));
            } finally {
                LibC.INSTANCE.close(fd);
            }
        }
    }

    public void createDirectory(Path root, String relativePath) throws IOException {
        Path relative = normalizedRelative(relativePath);
        requireLeaf(relative);
        requireSupported(root);
        try (ParentHandle parent = openParent(root, relative, false)) {
            hook.beforePrimitive(Primitive.CREATE_DIRECTORY, root, slash(relative));
            if (LibC.INSTANCE.mkdirat(parent.fd(), relative.getFileName().toString(), 0700) != 0) {
                throw io("mkdirat", slash(relative));
            }
        }
    }

    public void move(Path root, String sourceRelative, String targetRelative) throws IOException {
        Path source = normalizedRelative(sourceRelative);
        Path target = normalizedRelative(targetRelative);
        requireLeaf(source);
        requireLeaf(target);
        requireSupported(root);
        try (ParentHandle sourceParent = openParent(root, source, false);
             ParentHandle targetParent = openParent(root, target, false)) {
            int sourceFd = LibC.INSTANCE.openat(sourceParent.fd(), source.getFileName().toString(),
                    O_RDONLY | O_CLOEXEC | O_NOFOLLOW, 0);
            if (sourceFd < 0) throw io("openat", slash(source));
            try {
                requireSingleLinkWhenRegular(fstat(sourceFd, slash(source)), slash(source));
            } finally {
                LibC.INSTANCE.close(sourceFd);
            }
            hook.beforePrimitive(Primitive.MOVE, root, slash(target));
            if (LibC.INSTANCE.renameat2(sourceParent.fd(), source.getFileName().toString(),
                    targetParent.fd(), target.getFileName().toString(), RENAME_NOREPLACE) != 0) {
                throw io("renameat2", slash(target));
            }
        } catch (UnsatisfiedLinkError error) {
            throw new ConfinementUnavailableException("provider lacks renameat2(RENAME_NOREPLACE)", error);
        }
    }

    public void delete(Path root, String relativePath, boolean directory) throws IOException {
        Path relative = normalizedRelative(relativePath);
        requireLeaf(relative);
        requireSupported(root);
        try (ParentHandle parent = openParent(root, relative, false)) {
            int fd = LibC.INSTANCE.openat(parent.fd(), relative.getFileName().toString(),
                    O_RDONLY | O_CLOEXEC | O_NOFOLLOW | (directory ? O_DIRECTORY : 0), 0);
            if (fd < 0) throw io("openat", slash(relative));
            try {
                requireSingleLinkWhenRegular(fstat(fd, slash(relative)), slash(relative));
            } finally {
                LibC.INSTANCE.close(fd);
            }
            hook.beforePrimitive(Primitive.DELETE, root, slash(relative));
            if (LibC.INSTANCE.unlinkat(parent.fd(), relative.getFileName().toString(),
                    directory ? AT_REMOVEDIR : 0) != 0) {
                throw io("unlinkat", slash(relative));
            }
        }
    }

    public byte[] read(Path root, String relativePath) throws IOException {
        Path relative = normalizedRelative(relativePath);
        requireLeaf(relative);
        requireSupported(root);
        try (ParentHandle parent = openParent(root, relative, false)) {
            hook.beforePrimitive(Primitive.READ, root, slash(relative));
            int fd = LibC.INSTANCE.openat(parent.fd(), relative.getFileName().toString(),
                    O_RDONLY | O_CLOEXEC | O_NOFOLLOW, 0);
            if (fd < 0) throw io("openat", slash(relative));
            try {
                Stat stat = fstat(fd, slash(relative));
                requireRegularSingleLink(stat, slash(relative));
                if (stat.st_size.longValue() > Integer.MAX_VALUE) {
                    throw new IOException("file is too large: " + slash(relative));
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream((int) stat.st_size.longValue());
                byte[] buffer = new byte[BUFFER_BYTES];
                while (true) {
                    int read = LibC.INSTANCE.read(fd, buffer, buffer.length);
                    if (read < 0) throw io("read", slash(relative));
                    if (read == 0) break;
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            } finally {
                LibC.INSTANCE.close(fd);
            }
        }
    }

    /** Provider-level hard-link probe used by preflight/read paths. */
    public void requireSingleLink(Path path) throws IOException {
        try {
            Object value = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if (!(value instanceof Number number)) {
                throw new ConfinementUnavailableException("provider returned no numeric unix:nlink");
            }
            if (number.longValue() > 1) {
                throw new HardLinkDeniedException("multi-link file is not mutable: " + path.getFileName());
            }
        } catch (UnsupportedOperationException | IllegalArgumentException exception) {
            throw new ConfinementUnavailableException("provider does not expose unix:nlink", exception);
        }
    }

    private ParentHandle openParent(Path root, Path relative, boolean createParents) throws IOException {
        int rootFd = LibC.INSTANCE.open(root.toString(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        if (rootFd < 0) throw io("open", root.toString());
        List<Integer> opened = new ArrayList<>();
        opened.add(rootFd);
        int current = rootFd;
        Path parent = relative.getParent();
        if (parent != null) {
            for (Path segment : parent) {
                String name = segment.toString();
                int next = LibC.INSTANCE.openat(current, name,
                        O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW, 0);
                if (next < 0 && createParents && Native.getLastError() == ENOENT) {
                    if (LibC.INSTANCE.mkdirat(current, name, 0700) != 0
                            && Native.getLastError() != EEXIST) {
                        closeAll(opened);
                        throw io("mkdirat", name);
                    }
                    next = LibC.INSTANCE.openat(current, name,
                            O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW, 0);
                }
                if (next < 0) {
                    closeAll(opened);
                    throw io("openat", name);
                }
                opened.add(next);
                current = next;
            }
        }
        return new ParentHandle(current, opened);
    }

    private Stat fstat(int fd, String path) throws IOException {
        Stat stat = new Stat();
        if (LibC.INSTANCE.fstat(fd, stat) != 0) throw io("fstat", path);
        stat.read();
        return stat;
    }

    private void requireRegularSingleLink(Stat stat, String path) {
        if ((stat.st_mode & S_IFMT) != S_IFREG) {
            throw new IllegalArgumentException("target is not a regular file: " + path);
        }
        requireSingleLinkWhenRegular(stat, path);
    }

    private void requireSingleLinkWhenRegular(Stat stat, String path) {
        if ((stat.st_mode & S_IFMT) == S_IFREG && stat.st_nlink.longValue() > 1) {
            throw new HardLinkDeniedException("multi-link file is not mutable: " + path);
        }
    }

    private void writeAll(int fd, byte[] bytes, String path) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            int count = Math.min(BUFFER_BYTES, bytes.length - offset);
            byte[] chunk = offset == 0 && count == bytes.length
                    ? bytes : java.util.Arrays.copyOfRange(bytes, offset, offset + count);
            int written = LibC.INSTANCE.write(fd, chunk, count);
            if (written <= 0) throw io("write", path);
            offset += written;
        }
    }

    private Path normalizedRelative(String value) {
        if (value == null) throw new IllegalArgumentException("relative path is required");
        Path path = Path.of(value.replace('/', java.io.File.separatorChar)).normalize();
        if (path.isAbsolute() || path.toString().isBlank() || path.startsWith("..")) {
            throw new IllegalArgumentException("path must be a confined non-root relative path");
        }
        for (Path segment : path) {
            String text = segment.toString();
            if (text.equals(".") || text.equals("..") || text.isBlank()) {
                throw new IllegalArgumentException("path contains an ambiguous segment");
            }
        }
        return path;
    }

    private void requireLeaf(Path path) {
        if (path.getFileName() == null) throw new IllegalArgumentException("workspace root is protected");
    }

    private String slash(Path path) {
        return path.toString().replace(java.io.File.separatorChar, '/');
    }

    private IOException io(String operation, String path) {
        int errno = Native.getLastError();
        if (errno == EEXIST) return new java.nio.file.FileAlreadyExistsException(path);
        if (errno == ENOENT) return new java.nio.file.NoSuchFileException(path);
        return new IOException(operation + " failed for " + path + " (errno=" + errno + ")");
    }

    private void closeAll(List<Integer> descriptors) {
        for (int index = descriptors.size() - 1; index >= 0; index--) {
            LibC.INSTANCE.close(descriptors.get(index));
        }
    }

    private record ParentHandle(int fd, List<Integer> descriptors) implements AutoCloseable {
        @Override public void close() {
            for (int index = descriptors.size() - 1; index >= 0; index--) {
                LibC.INSTANCE.close(descriptors.get(index));
            }
        }
    }

    public static final class ConfinementUnavailableException extends IllegalStateException {
        public ConfinementUnavailableException(String message) { super(message); }
        public ConfinementUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class HardLinkDeniedException extends IllegalArgumentException {
        public HardLinkDeniedException(String message) { super(message); }
    }

    interface LibC extends Library {
        LibC INSTANCE = Native.load("c", LibC.class);
        int open(String path, int flags);
        int openat(int dirfd, String path, int flags, int mode);
        int mkdirat(int dirfd, String path, int mode);
        int renameat2(int olddirfd, String oldpath, int newdirfd, String newpath, int flags);
        int unlinkat(int dirfd, String path, int flags);
        int fstat(int fd, Stat stat);
        int ftruncate(int fd, long length);
        long lseek(int fd, long offset, int whence);
        int read(int fd, byte[] buffer, int count);
        int write(int fd, byte[] buffer, int count);
        int fsync(int fd);
        int close(int fd);
    }

    @Structure.FieldOrder({
        "st_dev", "st_ino", "st_nlink", "st_mode", "st_uid", "st_gid", "pad0",
        "st_rdev", "st_size", "st_blksize", "st_blocks", "st_atime", "st_atimeNsec",
        "st_mtime", "st_mtimeNsec", "st_ctime", "st_ctimeNsec", "reserved"
    })
    public static class Stat extends Structure {
        public NativeLong st_dev;
        public NativeLong st_ino;
        public NativeLong st_nlink;
        public int st_mode;
        public int st_uid;
        public int st_gid;
        public int pad0;
        public NativeLong st_rdev;
        public NativeLong st_size;
        public NativeLong st_blksize;
        public NativeLong st_blocks;
        public NativeLong st_atime;
        public NativeLong st_atimeNsec;
        public NativeLong st_mtime;
        public NativeLong st_mtimeNsec;
        public NativeLong st_ctime;
        public NativeLong st_ctimeNsec;
        public NativeLong[] reserved = new NativeLong[3];
    }
}
