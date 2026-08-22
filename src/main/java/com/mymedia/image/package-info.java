/**
 * 图片域：任意深度节点树、CBZ 流式读取、分页阅读、阅读进度。
 *
 * <p><b>依赖方向</b>：本模块<b>绝不</b>引用 {@code video}，也绝不引用后续的
 * {@code preview} / {@code metadata}——预览与刮削是订阅本模块事件、再调用本模块
 * 公开写回 API 的下游，依赖是单向的。与视频域共享的只有 {@code shared} 里的算法
 * （{@code NaturalSortKey}、{@code MaterializedPath}），<b>复用算法，不复用模型</b>。
 *
 * <p>下面这份 {@code allowedDependencies} 就是这条约束的强制点，由
 * {@code ModularityTests} 在测试阶段检查。{@code scan} 的跨模块契约分成两个命名接口
 * （{@code scan::spi} 与 {@code scan::events}），必须逐个列出，不能依赖 scan 的内部实现包。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Image",
        allowedDependencies = {"shared", "user", "library", "jobs", "scan", "scan::spi", "scan::events"})
package com.mymedia.image;