package de.schrell.quickdiskscan;

import java.nio.file.Path;
import java.util.List;

public final class VolumeDiscoveryTest {
    public static void main(String[] args) {
        VolumeDiscovery.Volume first = volume("volumes/first");
        VolumeDiscovery.Volume nested = volume("volumes/first/nested");
        VolumeDiscovery.Volume second = volume("volumes/second");
        List<VolumeDiscovery.Volume> volumes = List.of(first, nested, second);

        assert VolumeDiscovery.volumeForPath(volumes, Path.of("volumes/first/folder")) == first;
        assert VolumeDiscovery.volumeForPath(volumes, Path.of("volumes/first/nested/folder")) == nested;
        assert VolumeDiscovery.volumeForPath(volumes, Path.of("other/folder")) == null;
    }

    private static VolumeDiscovery.Volume volume(String path) {
        return new VolumeDiscovery.Volume(Path.of(path), path, "test", 1, 0);
    }
}
