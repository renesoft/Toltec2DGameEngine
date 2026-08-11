package org.toltec.editor.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Finds {@code src/main/resources} on disk (the editor writes real files
 * there, not into the packaged classpath) and hands out a scratch directory
 * for the live preview's temp ini files.
 */
public final class EditorPaths {

    private EditorPaths() {}

    private static Path resourcesRoot;
    private static Path previewScratchDir;

    /**
     * Resolves the Maven resources folder relative to wherever the editor
     * was launched from — the working directory when run via {@code mvn
     * javafx:run} or from an IDE run configuration is normally the project
     * root, but we also check a couple of likely relative locations and, as
     * a last resort, fall back to a folder next to the working directory so
     * the editor is always usable even outside of this exact project layout.
     */
    public static synchronized Path resourcesRoot() {
        if (resourcesRoot != null) return resourcesRoot;

        Path cwd = Path.of("").toAbsolutePath();
        Path[] candidates = {
                cwd.resolve("src/main/resources"),
                cwd.resolve("../src/main/resources"),
                cwd.getParent() != null ? cwd.getParent().resolve("src/main/resources") : null,
        };
        for (Path c : candidates) {
            if (c != null && Files.isDirectory(c)) {
                resourcesRoot = c.toAbsolutePath().normalize();
                return resourcesRoot;
            }
        }

        // Nothing matched a real Maven layout — make a sibling folder rather than fail outright.
        Path fallback = cwd.resolve("editor-resources");
        try {
            Files.createDirectories(fallback);
        } catch (IOException e) {
            throw new IllegalStateException("Could not locate or create a resources folder near " + cwd, e);
        }
        resourcesRoot = fallback.toAbsolutePath().normalize();
        return resourcesRoot;
    }

    public static synchronized Path previewScratchDir() {
        if (previewScratchDir != null) return previewScratchDir;
        try {
            previewScratchDir = Files.createTempDirectory("toltec-editor-preview");
        } catch (IOException e) {
            throw new IllegalStateException("Could not create a temp folder for the live preview", e);
        }
        return previewScratchDir;
    }
}
