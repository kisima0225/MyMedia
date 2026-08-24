package com.mymedia.preview;

import com.mymedia.jobs.JobQueue;
import org.springframework.stereotype.Service;

/**
 * 预览生成的排队入口，是 {@code preview} 模块对外的写入 API。
 *
 * <p>全部走 {@code dedup_key}：同一个目标反复排队只会得到同一个待办任务。
 * 事件监听器与扫描完成后的补齐逻辑可以放心地重复调用。
 */
@Service
public class PreviewTrigger {

    private final JobQueue jobQueue;

    PreviewTrigger(JobQueue jobQueue) {
        this.jobQueue = jobQueue;
    }

    public Long requestVideoPreview(Long videoFileId) {
        return enqueue(PreviewJobHandler.JOB_TYPE, PreviewTarget.VIDEO_FILE, videoFileId);
    }

    public Long requestImagePreview(Long imageNodeId) {
        return enqueue(PreviewJobHandler.JOB_TYPE, PreviewTarget.IMAGE_NODE, imageNodeId);
    }

    public Long requestSprite(Long videoFileId) {
        return enqueue(SpriteJobHandler.JOB_TYPE, PreviewTarget.VIDEO_FILE, videoFileId);
    }

    private Long enqueue(String jobType, PreviewTarget target, Long targetId) {
        String payload = "{\"target\":\"" + target.name() + "\",\"targetId\":" + targetId + "}";
        String dedupKey = jobType + ":" + target.name() + ":" + targetId;
        return jobQueue.enqueue(jobType, payload, dedupKey);
    }
}
