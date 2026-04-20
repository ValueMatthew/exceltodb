package com.exceltodb.service;

import com.exceltodb.model.ImportHeartbeat;
import com.exceltodb.model.ImportStage;
import com.exceltodb.model.ImportStatus;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ImportHeartbeatStore {

    private static final long RETAIN_AFTER_DONE_MS = 10 * 60 * 1000L; // 10 minutes

    private final Map<String, ImportHeartbeat> store = new ConcurrentHashMap<>();
    private final Map<String, Long> doneAt = new ConcurrentHashMap<>();

    public ImportHeartbeat get(String requestId) {
        cleanupIfNeeded();
        return store.get(requestId);
    }

    public void start(String requestId) {
        ImportHeartbeat hb = new ImportHeartbeat();
        hb.setRequestId(requestId);
        hb.setStatus(ImportStatus.RUNNING);
        hb.setStage(ImportStage.READING);
        hb.setUpdatedAt(System.currentTimeMillis());
        hb.setProcessedRows(0);
        hb.setMessage("");
        store.put(requestId, hb);
        doneAt.remove(requestId);
    }

    public void update(String requestId, ImportStage stage, long processedRows, String message) {
        ImportHeartbeat hb = store.get(requestId);
        if (hb == null) {
            start(requestId);
            hb = store.get(requestId);
        }
        hb.setStage(stage);
        hb.setProcessedRows(processedRows);
        hb.setUpdatedAt(System.currentTimeMillis());
        if (message != null) hb.setMessage(message);
    }

    public void success(String requestId, long processedRows) {
        ImportHeartbeat hb = store.get(requestId);
        if (hb == null) {
            start(requestId);
            hb = store.get(requestId);
        }
        hb.setStatus(ImportStatus.SUCCESS);
        hb.setStage(ImportStage.COMMITTING);
        hb.setProcessedRows(processedRows);
        hb.setUpdatedAt(System.currentTimeMillis());
        doneAt.put(requestId, hb.getUpdatedAt());
    }

    public void error(String requestId, long processedRows, String message) {
        ImportHeartbeat hb = store.get(requestId);
        if (hb == null) {
            start(requestId);
            hb = store.get(requestId);
        }
        hb.setStatus(ImportStatus.ERROR);
        hb.setProcessedRows(processedRows);
        hb.setUpdatedAt(System.currentTimeMillis());
        hb.setMessage(message == null ? "" : message);
        doneAt.put(requestId, hb.getUpdatedAt());
    }

    private void cleanupIfNeeded() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : doneAt.entrySet()) {
            if (now - e.getValue() > RETAIN_AFTER_DONE_MS) {
                String requestId = e.getKey();
                doneAt.remove(requestId);
                store.remove(requestId);
            }
        }
    }
}

