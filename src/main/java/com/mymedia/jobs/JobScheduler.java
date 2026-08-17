package com.mymedia.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final JobClaimService claimService;
    private final Map<String, JobHandler> handlersByType;
    private final String workerId;
    private final int batchSize;
    private final Duration leaseDuration;

    JobScheduler(JobClaimService claimService,
                 List<JobHandler> handlers,
                 @Value("${mymedia.jobs.batch-size:5}") int batchSize,
                 @Value("${mymedia.jobs.lease-duration:PT10M}") Duration leaseDuration) {
        this.claimService = claimService;
        this.handlersByType = handlers.stream()
                .collect(Collectors.toMap(JobHandler::jobType, Function.identity()));
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.workerId = buildWorkerId();
        log.info("任务调度器启动，workerId={}，已注册处理器={}", workerId, handlersByType.keySet());
    }

    @Scheduled(fixedDelayString = "${mymedia.jobs.poll-interval:PT5S}")
    void poll() {
        pollOnce();
    }

    /** 供测试直接触发一轮轮询，避免依赖定时器时序。 */
    void pollOnce() {
        int reclaimed = claimService.reclaimExpiredLeases();
        if (reclaimed > 0) {
            log.warn("回收了 {} 个租约过期的任务", reclaimed);
        }

        List<Job> claimed = claimService.claim(workerId, batchSize, leaseDuration);
        for (Job job : claimed) {
            execute(job);
        }
    }

    private void execute(Job job) {
        JobHandler handler = handlersByType.get(job.getType());
        if (handler == null) {
            String message = "没有注册处理该类型的 JobHandler: " + job.getType();
            log.error(message);
            claimService.recordFailure(job.getId(), workerId, message);
            return;
        }
        try {
            handler.handle(job);
            claimService.recordSuccess(job.getId(), workerId);
            log.debug("任务完成 id={} type={}", job.getId(), job.getType());
        } catch (Exception e) {
            log.warn("任务失败 id={} type={}，已记录失败结果", job.getId(), job.getType(), e);
            claimService.recordFailure(job.getId(), workerId, describe(e));
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + ": " + (message == null ? "(无消息)" : message);
    }

    private static String buildWorkerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
