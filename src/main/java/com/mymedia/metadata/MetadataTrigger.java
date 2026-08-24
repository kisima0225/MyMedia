package com.mymedia.metadata;

import com.mymedia.jobs.JobQueue;
import com.mymedia.library.LibraryDomain;
import org.springframework.stereotype.Service;

/** 刮削任务的排队入口。走 {@code dedup_key}，重复调用只会得到同一个待办任务。 */
@Service
public class MetadataTrigger {

    private final JobQueue jobQueue;

    MetadataTrigger(JobQueue jobQueue) {
        this.jobQueue = jobQueue;
    }

    public Long request(LibraryDomain domain, Long targetId) {
        String payload = "{\"domain\":\"" + domain.name() + "\",\"targetId\":" + targetId + "}";
        String dedupKey = MetadataFetchJobHandler.JOB_TYPE + ":" + domain.name() + ":" + targetId;
        return jobQueue.enqueue(MetadataFetchJobHandler.JOB_TYPE, payload, dedupKey);
    }
}
