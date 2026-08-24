package com.mymedia.shared;

/**
 * 一个条目的刮削状态。取值与 {@code video_item} / {@code image_node} 的
 * {@code scrape_status} CHECK 约束一一对应。
 *
 * <p>放在 {@code shared} 而不是各领域模块里：两个域用的是同一套状态机，
 * {@code metadata} 模块也要写它，重复定义两遍迟早会漂移。
 */
public enum ScrapeStatus {

    /** 所属库没有配置任何刮削器——不排任务，界面零刮削噪音。 */
    NOT_APPLICABLE,

    /** 待刮削。 */
    PENDING,

    /** 高置信度命中并已应用。 */
    MATCHED,

    /** 没找到。<b>这是正常状态不是错误</b>，界面安静回落到文件名元数据。 */
    NO_MATCH,

    /** 有候选但置信度不够，等用户确认。 */
    NEEDS_REVIEW,

    /** 网络故障或限流，会按退避重试。 */
    ERROR
}
