package com.mymedia.video;

/**
 * 一次媒体探测的结果，由 {@code preview} 模块填好后交回本模块写入。
 *
 * <p>每个字段都可能为 {@code null}：容器格式五花八门，缺时长、缺比特率、
 * 纯音频轨都属正常。
 */
public record VideoProbeData(
        Integer durationSeconds,
        Integer width,
        Integer height,
        String videoCodec,
        String audioCodec,
        Long bitrate,
        String container,
        String rawJson) {
}
