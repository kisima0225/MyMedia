/**
 * 派生资源生成：封面、缩略图、进度条雪碧图。
 *
 * <p><b>依赖方向是单向的</b>：本模块订阅 {@code video} / {@code image} 的领域事件
 * 并调用它们的公开写回 API，两个领域模块<b>绝不</b>反向引用本模块。
 * 这一点由 {@code ModularityTests} 强制。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Preview",
        allowedDependencies = {"shared", "library", "jobs", "scan"})
package com.mymedia.preview;