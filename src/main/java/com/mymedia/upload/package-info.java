/**
 * 分片上传。
 *
 * <p>独立成模块而不是并进 {@code library}：它有自己的状态机（会话 / 分片 / 合并）、
 * 自己的临时存储布局、自己的任务类型，而 {@code library} 是一个只管「库有哪些、
 * 谁能看」的薄模块。
 *
 * <p>依赖 {@code scan} 是为了两件事：秒传要查既有物理文件的指纹，
 * 合并落库后要触发一次增量扫描把新文件接进语义层。
 * <b>它不依赖 {@code video} 与 {@code image}</b>——上传只负责把字节放到正确的目录里，
 * 「这是一部电影还是一本漫画」由扫描链路照常判定。这是本项目
 * 「物理层共享、语义层分域」在上传这条链路上的又一次体现。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Upload",
        allowedDependencies = {"shared", "user", "library", "jobs", "scan"})
package com.mymedia.upload;
