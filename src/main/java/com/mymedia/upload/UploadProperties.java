package com.mymedia.upload;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/**
 * @param tempRoot  未合并的分片落在这里，独立于媒体库根目录——
 *                  半成品绝不能出现在会被扫描的目录里
 * @param chunkSize 服务端决定的分片大小，创建会话时下发给客户端
 * @param maxSize   单个文件的上界。默认 20GB：够放一部 4K 原盘，
 *                  又不至于让一次误操作把磁盘写满
 */
@ConfigurationProperties(prefix = "mymedia.upload")
record UploadProperties(
        @DefaultValue("./data/uploads") Path tempRoot,
        @DefaultValue("8388608") int chunkSize,
        @DefaultValue("21474836480") long maxSize) {
}
