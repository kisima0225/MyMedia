/**
 * 元数据：提供者链、刮削任务、待确认队列、用户编辑。
 *
 * <p><b>依赖方向是单向的</b>：本模块订阅 {@code video} / {@code image} 的领域事件
 * 并调用它们的公开写回 API，两个领域模块绝不反向引用本模块。
 *
 * <p><b>为什么这里不像 {@code scan} 那样做 SPI 倒置</b>：物理扫描真正与领域无关，
 * 倒置能让"加第三个域不改扫描代码"成立；而刮削本身就是领域特定的
 * （TMDB 管电影、Bangumi 管番剧与漫画），倒置只会把 if/else 换个地方摆，
 * 还多一层间接。详见 ADR-004。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Metadata",
        allowedDependencies = {"shared", "user", "library", "jobs", "scan", "scan::events",
                               "video", "video::events", "image", "image::events"})
package com.mymedia.metadata;
