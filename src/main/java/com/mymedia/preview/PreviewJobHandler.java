package com.mymedia.preview;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.mymedia.jobs.Job;
import com.mymedia.jobs.JobHandler;
import org.springframework.stereotype.Component;

/**
 * {@code PREVIEW_GENERATE} 的处理器。
 *
 * <p>两个域共用一个任务类型、按载荷里的 {@code target} 分派，而不是各建一个类型。
 * 理由：任务队列关心的是"有多少预览待生成"，不是"它是视频还是图片"；
 * 分两个类型会让运维视角多一层无意义的切分。
 */
@Component
class PreviewJobHandler implements JobHandler {

    static final String JOB_TYPE = "PREVIEW_GENERATE";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final VideoPreviewGenerator videoGenerator;
    private final ImagePreviewGenerator imageGenerator;

    PreviewJobHandler(VideoPreviewGenerator videoGenerator,
                      ImagePreviewGenerator imageGenerator) {
        this.videoGenerator = videoGenerator;
        this.imageGenerator = imageGenerator;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(Job job) throws Exception {
        JsonNode payload = MAPPER.readTree(job.getPayload());
        PreviewTarget target = PreviewTarget.valueOf(payload.path("target").asString());
        Long targetId = payload.path("targetId").asLong();

        switch (target) {
            case VIDEO_FILE -> videoGenerator.generate(targetId);
            case IMAGE_NODE -> imageGenerator.generate(targetId);
        }
    }
}
