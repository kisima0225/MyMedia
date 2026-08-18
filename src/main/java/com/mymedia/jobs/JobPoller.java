package com.mymedia.jobs;

/**
 * 手动触发一轮任务轮询的入口。
 *
 * <p>存在的理由是测试：依赖定时器时序的测试既慢又不稳定。
 * 生产代码不应调用它——定时轮询由 {@code JobScheduler} 负责。
 */
public interface JobPoller {

    /** 立即执行一轮抢占并异步提交任务处理，方法本身同步返回。 */
    void pollOnce();
}
