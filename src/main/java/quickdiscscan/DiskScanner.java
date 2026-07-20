package quickdiscscan;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class DiskScanner {
    enum SizeBasis {
        LOGICAL("Logisch"), PHYSICAL("Physisch");

        final String label;

        SizeBasis(String label) {
            this.label = label;
        }

        long bytes(ScanNode node) {
            return this == LOGICAL ? node.logicalBytes() : node.physicalBytes();
        }
    }

    record Config(Path root, int parallelism) {
        Config {
            Objects.requireNonNull(root);
            root = root.toAbsolutePath().normalize();
            if (parallelism < 1 || parallelism > 64) {
                throw new IllegalArgumentException("Parallelität muss zwischen 1 und 64 liegen");
            }
        }
    }

    record Snapshot(long entries, long files, long directories, long errors,
                    long logicalBytes, long physicalBytes, long offlineFiles,
                    long elapsedMillis, Path currentPath, boolean running) {}

    record Result(ScanNode root, Snapshot snapshot) {}

    private record FileIdentity(long device, long file) {}

    static final class ScanNode {
        private final ScanNode parent;
        private final Path rootPath;
        private final String name;
        private final boolean directory;
        private final boolean symbolicLink;
        private final boolean excludedVolume;
        private final boolean offlineSelf;
        private final long ownLogicalBytes;
        private final long ownPhysicalBytes;
        private volatile long logicalBytes;
        private volatile long physicalBytes;
        private volatile long offlineFiles;
        private volatile List<ScanNode> children = List.of();

        private ScanNode(ScanNode parent, Path rootPath, String name,
                         NativeDiskMetrics.Metadata metadata, long countedPhysicalBytes,
                         boolean excludedVolume) {
            this.parent = parent;
            this.rootPath = rootPath;
            this.name = name;
            this.directory = metadata.directory();
            this.symbolicLink = metadata.symbolicLink();
            this.excludedVolume = excludedVolume;
            this.offlineSelf = metadata.offline();
            this.ownLogicalBytes = metadata.regularFile() ? metadata.logicalBytes() : 0;
            this.ownPhysicalBytes = metadata.regularFile() ? countedPhysicalBytes : 0;
            this.logicalBytes = ownLogicalBytes;
            this.physicalBytes = ownPhysicalBytes;
            this.offlineFiles = offlineSelf && metadata.regularFile() ? 1 : 0;
        }

        String name() {
            return name;
        }

        ScanNode parent() {
            return parent;
        }

        boolean directory() {
            return directory;
        }

        boolean symbolicLink() {
            return symbolicLink;
        }

        boolean excludedVolume() {
            return excludedVolume;
        }

        boolean offlineSelf() {
            return offlineSelf;
        }

        long logicalBytes() {
            return logicalBytes;
        }

        long physicalBytes() {
            return physicalBytes;
        }

        long offlineFiles() {
            return offlineFiles;
        }

        List<ScanNode> children() {
            return children;
        }

        Path path() {
            if (parent == null) {
                return rootPath;
            }
            ArrayList<String> names = new ArrayList<>();
            ScanNode node = this;
            while (node.parent != null) {
                names.add(node.name);
                node = node.parent;
            }
            Path path = node.rootPath;
            for (int index = names.size() - 1; index >= 0; index--) {
                path = path.resolve(names.get(index));
            }
            return path;
        }

        String displayPath() {
            return path().toString();
        }

        List<ScanNode> sortedChildren(SizeBasis basis) {
            ArrayList<ScanNode> sorted = new ArrayList<>(children);
            sorted.sort(Comparator.comparingLong((ScanNode node) -> basis.bytes(node)).reversed()
                    .thenComparing(ScanNode::name, String.CASE_INSENSITIVE_ORDER));
            return sorted;
        }

        void removeChildren(Collection<ScanNode> removed) {
            if (removed.isEmpty()) {
                return;
            }
            children = children.stream().filter(node -> !removed.contains(node)).toList();
            for (ScanNode node = this; node != null; node = node.parent) {
                node.recalculate();
            }
        }

        private void recalculate() {
            long logical = ownLogicalBytes;
            long physical = ownPhysicalBytes;
            long offline = offlineSelf && !directory ? 1 : 0;
            for (ScanNode child : children) {
                logical += child.logicalBytes;
                physical += child.physicalBytes;
                offline += child.offlineFiles;
            }
            logicalBytes = logical;
            physicalBytes = physical;
            offlineFiles = offline;
        }

        private boolean isDescendantOf(ScanNode possibleAncestor) {
            for (ScanNode node = this; node != null; node = node.parent) {
                if (node == possibleAncestor) {
                    return true;
                }
            }
            return false;
        }
    }

    private final Config config;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicLong entries = new AtomicLong();
    private final AtomicLong files = new AtomicLong();
    private final AtomicLong directories = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong logicalBytes = new AtomicLong();
    private final AtomicLong physicalBytes = new AtomicLong();
    private final AtomicLong offlineFiles = new AtomicLong();
    private final Collection<FileIdentity> countedHardLinks = ConcurrentHashMap.newKeySet();
    private final Collection<DirectoryStream<Path>> openDirectories = ConcurrentHashMap.newKeySet();
    private volatile long startedNanos;
    private volatile boolean running;
    private volatile Path currentPath;
    private volatile ScanNode root;
    private volatile ForkJoinPool pool;

    DiskScanner(Config config) {
        this.config = config;
    }

    Result scan() throws IOException {
        startedNanos = System.nanoTime();
        running = true;
        currentPath = config.root();
        try {
            checkCancelled();
            NativeDiskMetrics.Metadata rootMetadata = NativeDiskMetrics.read(config.root());
            if (!rootMetadata.directory()) {
                throw new IOException("Kein Verzeichnis: " + config.root());
            }
            root = new ScanNode(null, config.root(), rootName(config.root()), rootMetadata, 0, false);
            directories.incrementAndGet();
            entries.incrementAndGet();

            pool = new ForkJoinPool(config.parallelism());
            try {
                pool.submit(new DirectoryTask(root, config.root(), rootMetadata.device())).get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CancellationException();
            } catch (ExecutionException exception) {
                if (cancelled.get()) {
                    throw new CancellationException();
                }
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IOException("Scan fehlgeschlagen", cause);
            }
            checkCancelled();
            return new Result(root, snapshot(false));
        } finally {
            running = false;
            if (pool != null) {
                pool.shutdownNow();
            }
        }
    }

    void cancel() {
        cancelled.set(true);
        ForkJoinPool activePool = pool;
        if (activePool != null) {
            activePool.shutdownNow();
        }
        List<DirectoryStream<Path>> streams = List.copyOf(openDirectories);
        if (!streams.isEmpty()) {
            Thread.ofVirtual().name("scan-directory-close").start(
                    () -> streams.forEach(DiskScanner::close));
        }
    }

    ScanNode root() {
        return root;
    }

    Snapshot snapshot() {
        return snapshot(running);
    }

    void recordDeleted(Collection<ScanNode> deletedRoots) {
        long removedEntries = 0;
        long removedFiles = 0;
        long removedDirectories = 0;
        long removedLogical = 0;
        long removedPhysical = 0;
        long removedOffline = 0;
        ArrayDeque<ScanNode> pending = new ArrayDeque<>(deletedRoots);
        while (!pending.isEmpty()) {
            ScanNode node = pending.removeFirst();
            removedEntries++;
            if (node.directory) {
                removedDirectories++;
                pending.addAll(node.children);
            } else if (!node.symbolicLink) {
                removedFiles++;
                removedLogical += node.ownLogicalBytes;
                removedPhysical += node.ownPhysicalBytes;
                if (node.offlineSelf) {
                    removedOffline++;
                }
            }
        }
        entries.addAndGet(-removedEntries);
        files.addAndGet(-removedFiles);
        directories.addAndGet(-removedDirectories);
        logicalBytes.addAndGet(-removedLogical);
        physicalBytes.addAndGet(-removedPhysical);
        offlineFiles.addAndGet(-removedOffline);
    }

    private Snapshot snapshot(boolean isRunning) {
        long elapsed = startedNanos == 0 ? 0 : (System.nanoTime() - startedNanos) / 1_000_000;
        return new Snapshot(entries.get(), files.get(), directories.get(), errors.get(),
                logicalBytes.get(), physicalBytes.get(), offlineFiles.get(), elapsed,
                currentPath, isRunning);
    }

    private void checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }

    private static String rootName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    private static void close(DirectoryStream<Path> stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Best effort: some providers can still be blocked in native code.
        }
    }

    @SuppressWarnings("serial")
    private final class DirectoryTask extends RecursiveAction {
        private final ScanNode node;
        private final Path path;
        private final long rootDevice;

        private DirectoryTask(ScanNode node, Path path, long rootDevice) {
            this.node = node;
            this.path = path;
            this.rootDevice = rootDevice;
        }

        @Override
        protected void compute() {
            checkCancelled();
            ArrayList<ScanNode> children = new ArrayList<>();
            ArrayList<DirectoryTask> directoryTasks = new ArrayList<>();
            long seen = entries.get();

            DirectoryStream<Path> stream = null;
            try {
                stream = Files.newDirectoryStream(path);
                openDirectories.add(stream);
                for (Path childPath : stream) {
                    checkCancelled();
                    try {
                        NativeDiskMetrics.Metadata metadata = NativeDiskMetrics.read(childPath);
                        boolean otherVolume = metadata.directory() && rootDevice != 0
                                && metadata.device() != 0 && metadata.device() != rootDevice;
                        long countedPhysical = metadata.physicalBytes();
                        if (metadata.regularFile() && metadata.linkCount() > 1
                                && !countedHardLinks.add(new FileIdentity(
                                        metadata.device(), metadata.fileIdentity()))) {
                            countedPhysical = 0;
                        }
                        ScanNode child = new ScanNode(node, null, childPath.getFileName().toString(),
                                metadata, countedPhysical, otherVolume);
                        children.add(child);
                        long count = entries.incrementAndGet();
                        if ((count & 255) == 0 || count - seen > 1_024) {
                            currentPath = childPath;
                            seen = count;
                        }

                        if (metadata.directory()) {
                            directories.incrementAndGet();
                            if (!otherVolume && !metadata.symbolicLink()) {
                                directoryTasks.add(new DirectoryTask(child, childPath, rootDevice));
                            }
                        } else if (metadata.regularFile()) {
                            files.incrementAndGet();
                            logicalBytes.addAndGet(metadata.logicalBytes());
                            physicalBytes.addAndGet(countedPhysical);
                            if (metadata.offline()) {
                                offlineFiles.incrementAndGet();
                            }
                        }
                    } catch (IOException | SecurityException ignored) {
                        errors.incrementAndGet();
                    }
                }
            } catch (DirectoryIteratorException exception) {
                if (cancelled.get()) {
                    throw new CancellationException();
                }
                errors.incrementAndGet();
            } catch (IOException | SecurityException ignored) {
                errors.incrementAndGet();
            } catch (RuntimeException exception) {
                if (cancelled.get()) {
                    throw new CancellationException();
                }
                throw exception;
            } finally {
                if (stream != null) {
                    openDirectories.remove(stream);
                    close(stream);
                }
            }

            node.children = List.copyOf(children);
            checkCancelled();
            invokeAll(directoryTasks);
            checkCancelled();
            node.recalculate();
        }
    }
}
