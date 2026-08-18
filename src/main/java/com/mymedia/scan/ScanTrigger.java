package com.mymedia.scan;

import com.mymedia.jobs.JobQueue;
import org.springframework.stereotype.Service;

/** {@code scan} 模块对外暴露的扫描触发入口。 */
@Service
public class ScanTrigger {

    private final JobQueue jobQueue;

    ScanTrigger(JobQueue jobQueue) {
        this.jobQueue = jobQueue;
    }

    /**
     * 请求扫描指定媒体库。
     *
     * <p>同一个库的未完成扫描使用同一个去重键；上一次扫描完成后可以再次入队。
     *
     * @return 任务 id，可能是既有任务的 id
     */
    public Long requestScan(Long libraryId) {
        return jobQueue.enqueue(
                LibraryScanJobHandler.JOB_TYPE,
                "{\"libraryId\":" + libraryId + "}",
                "library-scan:" + libraryId);
    }
}
