package com.mymedia.upload;

import com.mymedia.jobs.Job;
import com.mymedia.jobs.JobHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code UPLOAD_ASSEMBLE}：把到齐的分片合并入库。
 *
 * <p>排成任务而不是挂在「最后一片」那个请求上，为的是从 {@code job} 表白拿
 * 重试、可观测与崩溃恢复三样东西（ADR-003）。
 */
@Component
class UploadAssembleJobHandler implements JobHandler {

    static final String JOB_TYPE = "UPLOAD_ASSEMBLE";

    private final UploadAssembler assembler;
    private final ObjectMapper objectMapper;

    UploadAssembleJobHandler(UploadAssembler assembler, ObjectMapper objectMapper) {
        this.assembler = assembler;
        this.objectMapper = objectMapper;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(Job job) throws Exception {
        JsonNode payload = objectMapper.readTree(job.getPayload());
        JsonNode sessionIdNode = payload.get("sessionId");
        if (sessionIdNode == null || !sessionIdNode.canConvertToLong()) {
            throw new IllegalArgumentException(
                    "UPLOAD_ASSEMBLE 任务缺少 sessionId: " + job.getPayload());
        }
        assembler.assemble(sessionIdNode.asLong());
    }
}
