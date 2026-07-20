package quickdiscscan;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

public final class DiskScannerTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("quickdiscscan-test-");
        try {
            Path alphaPath = root.resolve("alpha.bin");
            Files.write(alphaPath, new byte[4_097]);
            Path nested = Files.createDirectories(root.resolve("nested/deeper"));
            Path betaPath = nested.resolve("beta.bin");
            Files.write(betaPath, new byte[8_193]);
            Path sparse = root.resolve("sparse.bin");
            try (SeekableByteChannel channel = Files.newByteChannel(sparse,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.position(1_048_575);
                channel.write(ByteBuffer.wrap(new byte[] {1}));
            }
            Path zeroBlocks = root.resolve("zero-blocks.bin");
            try (RandomAccessFile file = new RandomAccessFile(zeroBlocks.toFile(), "rw")) {
                file.setLength(2_097_152);
            }
            boolean hardLinkCreated = false;
            try {
                Files.createLink(root.resolve("alpha-link.bin"), alphaPath);
                hardLinkCreated = true;
            } catch (IOException | UnsupportedOperationException ignored) {
                // Some test file systems do not support hard links.
            }

            DiskScanner scanner = new DiskScanner(new DiskScanner.Config(root, 4));
            DiskScanner.Result result = scanner.scan();
            assert result.snapshot().files() == 4 + (hardLinkCreated ? 1 : 0) : result.snapshot();
            assert result.snapshot().directories() == 3 : result.snapshot();
            assert result.snapshot().logicalBytes() == 4_097 + 8_193 + 1_048_576 + 2_097_152
                    + (hardLinkCreated ? 4_097 : 0)
                    : result.snapshot();
            assert result.root().logicalBytes() == result.snapshot().logicalBytes();
            assert result.root().physicalBytes() == result.snapshot().physicalBytes();
            assert result.root().children().size() == 4 + (hardLinkCreated ? 1 : 0);

            NativeDiskMetrics.Metadata sparseMetadata = NativeDiskMetrics.read(sparse);
            assert sparseMetadata.logicalBytes() == 1_048_576 : sparseMetadata;
            assert sparseMetadata.physicalBytes() >= 0 : sparseMetadata;
            NativeDiskMetrics.Metadata zeroBlockMetadata = NativeDiskMetrics.read(zeroBlocks);
            if (zeroBlockMetadata.logicalBytes() > 0 && zeroBlockMetadata.physicalBytes() == 0) {
                assert zeroBlockMetadata.offline() : zeroBlockMetadata;
            }
            long uniquePhysical = NativeDiskMetrics.read(alphaPath).physicalBytes()
                    + NativeDiskMetrics.read(betaPath).physicalBytes()
                    + sparseMetadata.physicalBytes() + zeroBlockMetadata.physicalBytes();
            if (hardLinkCreated && NativeDiskMetrics.nativeAvailable()) {
                assert result.snapshot().physicalBytes() == uniquePhysical : result.snapshot();
            }

            DiskScanner.ScanNode sparseNode = result.root().children().stream()
                    .filter(node -> node.name().equals("sparse.bin")).findFirst().orElseThrow();
            scanner.recordDeleted(java.util.List.of(sparseNode));
            result.root().removeChildren(java.util.List.of(sparseNode));
            assert scanner.snapshot().files() == 3 + (hardLinkCreated ? 1 : 0) : scanner.snapshot();
            assert result.root().logicalBytes() == result.snapshot().logicalBytes() - 1_048_576;

            DiskScanner cancelled = new DiskScanner(new DiskScanner.Config(root, 2));
            cancelled.cancel();
            try {
                cancelled.scan();
                throw new AssertionError("Vorzeitig abgebrochener Scan wurde ausgeführt");
            } catch (CancellationException expected) {
                // expected
            }
            testActiveCancellation(root);
            System.out.println("DiskScannerTest: OK (native metrics: "
                    + NativeDiskMetrics.nativeAvailable() + ")");
        } finally {
            deleteTree(root);
        }
    }

    private static void testActiveCancellation(Path parent) throws Exception {
        Path cancelRoot = Files.createDirectory(parent.resolve("cancel-tree"));
        for (int directory = 0; directory < 64; directory++) {
            Path child = Files.createDirectory(cancelRoot.resolve("d" + directory));
            for (int file = 0; file < 256; file++) {
                Files.createFile(child.resolve("f" + file));
            }
        }

        DiskScanner scanner = new DiskScanner(new DiskScanner.Config(cancelRoot, 4));
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().daemon(true).name("active-cancel-test").start(() -> {
            try {
                scanner.scan();
            } catch (Throwable exception) {
                outcome.set(exception);
            }
        });

        long deadline = System.nanoTime() + 5_000_000_000L;
        while (thread.isAlive() && scanner.snapshot().entries() < 200 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assert thread.isAlive() : "Testscan endete vor dem aktiven Abbruch";
        scanner.cancel();
        thread.interrupt();
        thread.join(2_000);
        assert !thread.isAlive() : "Aktiver Scan reagiert nicht innerhalb von zwei Sekunden";
        assert outcome.get() instanceof CancellationException : outcome.get();
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
