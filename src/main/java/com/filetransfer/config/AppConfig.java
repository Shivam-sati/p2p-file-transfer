package com.filetransfer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
@Slf4j
public class AppConfig {

    @Value("${app.storage.base-path}")
    private String basePath;

    @Value("${app.upload.chunk-size-bytes}")
    private long chunkSizeBytes;

    @Value("${app.upload.max-file-size-bytes}")
    private long maxFileSizeBytes;

    @Value("${app.share.default-expiry-hours}")
    private int defaultExpiryHours;

    @Value("${app.share.code-length}")
    private int shareCodeLength;

    @Bean
    public Path storagePath() throws IOException {
        Path path = Paths.get(basePath).toAbsolutePath().normalize();
        Files.createDirectories(path.resolve("chunks"));
        Files.createDirectories(path.resolve("files"));
        log.info("Storage root: {}", path);
        return path;
    }

    @Bean
    public AppProperties appProperties() {
        return new AppProperties(
                chunkSizeBytes,
                maxFileSizeBytes,
                defaultExpiryHours,
                shareCodeLength);
    }

    public record AppProperties(
            long chunkSizeBytes,
            long maxFileSizeBytes,
            int defaultExpiryHours,
            int shareCodeLength) {
    }
}