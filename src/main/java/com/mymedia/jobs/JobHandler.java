package com.mymedia.jobs;

/**
 * 任务处理器的服务提供接口。
 *
 * <p>其他模块通过实现本接口并注册为 Spring bean 来接管某一类任务，
 * {@code jobs} 模块不需要知道它们的存在——依赖方向是单向的。
 * 这使得新增一类后台任务无需修改调度器代码。
 */
public interface JobHandler {

    /** 本处理器负责的任务类型，与 {@code JobQueue.enqueue} 的 type 参数对应。 */
    String jobType();

    /**
     * 执行任务。抛出任何异常都视为失败，调度器会按指数退避安排重试，
     * 超过最大尝试次数后终结为 FAILED。
     */
    void handle(Job job) throws Exception;
}
