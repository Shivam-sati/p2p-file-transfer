package com.filetransfer.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Centralises all path logic so no other class constructs raw strings.
 *
 * Layout on disk:
 *   {basePath}/
 *     chunks/
 *       {fileId}/
 *         chunk_{index}.part
 *     files/
 *       {fileId}/
 *         {sanitisedName}
 */
@Slf4j
public final class StorageUtil {

    private StorageUtil() {}

    /**
     * Resolve and create (if needed) the directory that holds chunks for one file.
     */
    public static Path chunkDir(Path basePath, UUID fileId) throws IOException {
        Path dir = basePath.resolve("chunks").resolve(fileId.toString());
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * Path to a specific chunk file. Does not create anything.
     */
    public static Path chunkPath(Path basePath, UUID fileId, int chunkIndex) throws IOException {
        return chunkDir(basePath, fileId).resolve("chunk_" + chunkIndex + ".part");
    }

    /**
     * Resolve and create (if needed) the directory for the final merged file.
     */
    public static Path fileDir(Path basePath, UUID fileId) throws IOException {
        Path dir = basePath.resolve("files").resolve(fileId.toString());
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * Path to the final assembled file.
     */
    public static Path mergedFilePath(Path basePath, UUID fileId, String sanitisedName) throws IOException {
        return fileDir(basePath, fileId).resolve(sanitisedName);
    }

    /**
     * Strip path separators and dangerous characters from an uploaded filename.
     * Keeps alphanumerics, dots, hyphens, and underscores only.
     * Appends the fileId prefix to guarantee uniqueness across uploads.
     */
    public static String sanitise(String originalName, UUID fileId) {
        // Extract extension safely
        int dot = originalName.lastIndexOf('.');
        String base = dot > 0 ? originalName.substring(0, dot) : originalName;
        String ext  = dot > 0 ? originalName.substring(dot)    : "";

        String cleanBase = base.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        String cleanExt  = ext .replaceAll("[^a-zA-Z0-9.]",     "");

        // Truncate base to avoid excessively long filenames
        if (cleanBase.length() > 100) cleanBase = cleanBase.substring(0, 100);

        return fileId.toString().substring(0, 8) + "_" + cleanBase + cleanExt;
    }

    /**
     * Delete a directory and all its contents recursively.
     * Silently ignores non-existent paths.
     */
    public static void deleteRecursively(Path path) {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); }
                    catch (IOException e) { log.warn("Could not delete {}: {}", p, e.getMessage()); }
                });
        } catch (IOException e) {
            log.warn("Failed recursive delete of {}: {}", path, e.getMessage());
        }
    }
}