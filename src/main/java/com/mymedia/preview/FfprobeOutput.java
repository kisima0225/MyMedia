package com.mymedia.preview;

/**
 * ffprobe 探测结果中本项目关心的部分。
 *
 * <p>每个字段都可能为 {@code null}——媒体文件的容器五花八门，
 * 缺时长、缺比特率、纯音频都是正常情况，不是错误。
 */
record FfprobeOutput(
        Integer durationSeconds,
        Integer width,
        Integer height,
        String videoCodec,
        String audioCodec,
        Long bitrate,
        String container,
        String rawJson) {
}