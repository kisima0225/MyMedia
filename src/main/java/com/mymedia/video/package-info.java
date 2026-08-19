/**
 * 视频域：语义模型、文件名规则、Range 流式播放、播放进度、目录树视图。
 *
 * <p><b>依赖方向</b>：本模块<b>绝不</b>引用 {@code image}，也绝不引用后续的
 * {@code preview} / {@code metadata}——预览与刮削是订阅本模块事件、再调用本模块
 * 公开写回 API 的下游，依赖是单向的。与图片域共享的只有 {@code shared} 里的算法
 * （{@code NaturalSortKey}、{@code MaterializedPath}），<b>复用算法，不复用模型</b>。
 *
 * <p>下面这份 {@code allowedDependencies} 就是这条约束的强制点，由
 * {@code ModularityTests} 在测试阶段检查。{@code scan} 的跨模块契约分成两个命名接口
 * （{@code scan::spi} 与 {@code scan::events}），必须逐个列出，不能依赖 scan 的内部实现包。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Video",
        allowedDependencies = {"shared", "user", "library", "scan", "scan::spi", "scan::events"})
package com.mymedia.video;
