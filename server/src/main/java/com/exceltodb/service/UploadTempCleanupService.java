package com.exceltodb.service;

import com.exceltodb.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Set;

@Service
public class UploadTempCleanupService {

    private static final Logger log = LoggerFactory.getLogger(UploadTempCleanupService.class);

    private final AppConfig appConfig;
    private final ExcelParserService excelParserService;

    public UploadTempCleanupService(AppConfig appConfig, ExcelParserService excelParserService) {
        this.appConfig = appConfig;
        this.excelParserService = excelParserService;
    }

    @Scheduled(fixedDelayString = "#{@appConfig.uploadCleanup.interval.toMillis()}")
    public void cleanup() {
        AppConfig.UploadCleanupConfig cfg = appConfig.getUploadCleanup();
        if (cfg == null || !cfg.isEnabled()) {
            return;
        }

        Duration ttl = cfg.getTtl() == null ? Duration.ofHours(1) : cfg.getTtl();
        long now = System.currentTimeMillis();
        long expiredBefore = now - ttl.toMillis();

        Path dir = Paths.get(appConfig.getUploadTempPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            return;
        }

        Set<Path> active = excelParserService.snapshotActiveUploadPaths().stream()
                .map(p -> p.toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toSet());

        int scanned = 0;
        int candidates = 0;
        int deleted = 0;
        long started = System.currentTimeMillis();

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                scanned++;
                try {
                    if (!Files.isRegularFile(p)) {
                        continue;
                    }
                    Path norm = p.toAbsolutePath().normalize();
                    if (active.contains(norm)) {
                        continue;
                    }

                    FileTime lm = Files.getLastModifiedTime(p);
                    if (lm.toMillis() >= expiredBefore) {
                        continue;
                    }

                    candidates++;
                    if (Files.deleteIfExists(p)) {
                        deleted++;
                    }
                } catch (Exception e) {
                    log.warn("Upload cleanup failed for file: {}", p.getFileName(), e);
                }
            }
        } catch (Exception e) {
            log.warn("Upload cleanup scan failed for dir: {}", dir, e);
            return;
        } finally {
            long tookMs = System.currentTimeMillis() - started;
            log.info("Upload cleanup finished. dir={}, ttl={}, scanned={}, candidates={}, deleted={}, tookMs={}",
                    dir, ttl, scanned, candidates, deleted, tookMs);
        }
    }
}

