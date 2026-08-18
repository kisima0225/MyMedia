package com.mymedia.scan;

import com.mymedia.jobs.Job;
import com.mymedia.jobs.JobHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class LibraryScanJobHandler implements JobHandler {

    static final String JOB_TYPE = "LIBRARY_SCAN";

    private final LibraryScanner scanner;
    private final ObjectMapper objectMapper;

    LibraryScanJobHandler(LibraryScanner scanner, ObjectMapper objectMapper) {
        this.scanner = scanner;
        this.objectMapper = objectMapper;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(Job job) throws Exception {
        JsonNode payload = objectMapper.readTree(job.getPayload());
        JsonNode libraryIdNode = payload.get("libraryId");
        if (libraryIdNode == null || !libraryIdNode.canConvertToLong()) {
            throw new IllegalArgumentException("LIBRARY_SCAN 任务缺少 libraryId: " + job.getPayload());
        }
        scanner.scan(libraryIdNode.asLong());
    }
}
