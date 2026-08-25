/**
 * 跨域的 Web 层：全局搜索，将来还有静态资源托管与 OpenAPI 文档（spec §4.2）。
 *
 * <p><b>全局搜索是本项目唯一需要同时看见两个域的东西</b>，因此也是唯一
 * 可以同时依赖 {@code video} 与 {@code image} 的地方。把它塞进任何一个领域模块
 * 都会让其中一个依赖另一个，直接违背域分区（spec §5.2）。
 *
 * <p>结果<b>分区返回、不混排</b>：两个域的卡片布局、比例、可用操作都不一样，
 * 混排之后前端第一件事就是把它们再拆开。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Web",
        allowedDependencies = {"shared", "user", "video", "image"})
package com.mymedia.web;
