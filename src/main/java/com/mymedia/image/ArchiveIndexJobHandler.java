package com.mymedia.image;

import com.mymedia.jobs.Job;
import com.mymedia.jobs.JobHandler;
import org.springframework.stereotype.Component;

// 临时占位，Task 4 会替换为完整实现
@Component
class ArchiveIndexJobHandler implements JobHandler {

    static final String JOB_TYPE = "ARCHIVE_INDEX";

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(Job job) {
        // Task 4 实现
    }
}