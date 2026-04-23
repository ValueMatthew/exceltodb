package com.exceltodb.service;

import com.exceltodb.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadTempCleanupServiceTest {

    @TempDir
    Path dir;

    static class FakeExcelParserService extends ExcelParserService {
        private Set<Path> active = Set.of();

        FakeExcelParserService(AppConfig cfg) {
            super(cfg);
        }

        void setActive(Set<Path> active) {
            this.active = active;
        }

        @Override
        public Set<Path> snapshotActiveUploadPaths() {
            return active;
        }
    }

    @Test
    void deletesExpiredInactiveFilesOnly() throws Exception {
        AppConfig cfg = new AppConfig();
        cfg.setUploadTempPath(dir.toString());

        AppConfig.UploadCleanupConfig cleanup = new AppConfig.UploadCleanupConfig();
        cleanup.setEnabled(true);
        cleanup.setTtl(Duration.ofHours(1));
        cleanup.setInterval(Duration.ofMinutes(10));
        cfg.setUploadCleanup(cleanup);

        FakeExcelParserService eps = new FakeExcelParserService(cfg);
        UploadTempCleanupService svc = new UploadTempCleanupService(cfg, eps);

        long now = System.currentTimeMillis();

        Path expired = dir.resolve("expired.csv");
        Files.writeString(expired, "x");
        Files.setLastModifiedTime(expired, FileTime.fromMillis(now - Duration.ofHours(2).toMillis()));

        Path fresh = dir.resolve("fresh.csv");
        Files.writeString(fresh, "y");
        Files.setLastModifiedTime(fresh, FileTime.fromMillis(now - Duration.ofMinutes(10).toMillis()));

        Path activeExpired = dir.resolve("active-expired.csv");
        Files.writeString(activeExpired, "z");
        Files.setLastModifiedTime(activeExpired, FileTime.fromMillis(now - Duration.ofHours(2).toMillis()));
        eps.setActive(Set.of(activeExpired.toAbsolutePath().normalize()));

        svc.cleanup();

        assertFalse(Files.exists(expired), "expired inactive should be deleted");
        assertTrue(Files.exists(fresh), "fresh should remain");
        assertTrue(Files.exists(activeExpired), "active file should be skipped");
    }
}

