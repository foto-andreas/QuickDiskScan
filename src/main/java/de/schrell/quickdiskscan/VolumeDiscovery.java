package de.schrell.quickdiskscan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static de.schrell.quickdiskscan.I18n.text;

final class VolumeDiscovery {
    private static final Set<String> LINUX_PSEUDO_FILE_SYSTEMS = Set.of(
            "proc", "sysfs", "devtmpfs", "devpts", "tmpfs", "cgroup", "cgroup2",
            "pstore", "securityfs", "debugfs", "tracefs", "configfs", "fusectl",
            "mqueue", "hugetlbfs", "rpc_pipefs", "autofs");

    record Volume(Path path, String name, String type, long totalBytes, long usableBytes) {
        long usedBytes() {
            return Math.max(0, totalBytes - usableBytes);
        }

        double usedFraction() {
            return totalBytes == 0 ? 0 : Math.min(1, (double) usedBytes() / totalBytes);
        }

        @Override
        public String toString() {
            String label = name == null || name.isBlank() ? path.toString() : name;
            return label + "  (" + path + ")";
        }
    }

    private VolumeDiscovery() {}

    static List<Volume> discover() {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        FileSystems.getDefault().getRootDirectories().forEach(path -> paths.add(path.toAbsolutePath().normalize()));

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            addMacVolumes(paths);
        } else if (os.contains("linux")) {
            addLinuxMounts(paths);
        }

        ArrayList<Volume> volumes = new ArrayList<>();
        for (Path path : paths) {
            try {
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                FileStore store = Files.getFileStore(path);
                volumes.add(new Volume(path, friendlyName(path, store, os), store.type(),
                        store.getTotalSpace(), store.getUsableSpace()));
            } catch (IOException | SecurityException ignored) {
                // A disappearing removable volume is normal during discovery.
            }
        }
        return List.copyOf(volumes);
    }

    static Volume volumeForPath(List<Volume> volumes, Path path) {
        Volume match = null;
        for (Volume volume : volumes) {
            if (path.startsWith(volume.path()) && (match == null
                    || volume.path().getNameCount() > match.path().getNameCount())) {
                match = volume;
            }
        }
        return match;
    }

    private static String friendlyName(Path path, FileStore store, String os) {
        Path fileName = path.getFileName();
        if (fileName != null && !fileName.toString().isBlank()) {
            return fileName.toString();
        }
        if (os.contains("mac")) {
            return text("Systemvolume", "System volume");
        }
        String storeName = store.name();
        return storeName == null || storeName.isBlank() ? path.toString() : storeName;
    }

    private static void addMacVolumes(Set<Path> paths) {
        Path volumes = Path.of("/Volumes");
        if (!Files.isDirectory(volumes)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(volumes)) {
            for (Path path : stream) {
                paths.add(path.toAbsolutePath().normalize());
            }
        } catch (IOException | SecurityException ignored) {
            // Root remains available.
        }
    }

    private static void addLinuxMounts(Set<Path> paths) {
        Path mountInfo = Path.of("/proc/self/mountinfo");
        if (!Files.isReadable(mountInfo)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(mountInfo, StandardCharsets.UTF_8)) {
                int separator = line.indexOf(" - ");
                if (separator < 0) {
                    continue;
                }
                String[] left = line.substring(0, separator).split(" ");
                String[] right = line.substring(separator + 3).split(" ");
                if (left.length < 5 || right.length == 0 || LINUX_PSEUDO_FILE_SYSTEMS.contains(right[0])) {
                    continue;
                }
                paths.add(Path.of(unescapeMountPath(left[4])).toAbsolutePath().normalize());
            }
        } catch (IOException | RuntimeException ignored) {
            // Default roots are sufficient as a fallback.
        }
    }

    private static String unescapeMountPath(String path) {
        return path.replace("\\040", " ")
                .replace("\\011", "\t")
                .replace("\\012", "\n")
                .replace("\\134", "\\");
    }
}
