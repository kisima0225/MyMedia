package com.mymedia.upload;

/**
 * 上传会话的状态。
 *
 * <p>没有 {@code PENDING}：会话一创建就可以收分片了，
 * 多一个「已创建但还不能用」的状态只会让客户端多一次轮询。
 */
public enum UploadStatus {

    /** 正在收分片。秒传未命中的会话从这里开始。 */
    RECEIVING,
    /** 分片到齐，合并任务已入队。 */
    ASSEMBLING,
    /** 文件已落进媒体库（或秒传命中，一个字节都没传）。 */
    COMPLETED,
    /** 合并或校验失败，{@code last_error} 里有原因。 */
    FAILED
}
