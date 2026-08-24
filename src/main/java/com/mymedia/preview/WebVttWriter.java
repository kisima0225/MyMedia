package com.mymedia.preview;

import java.util.Locale;

/**
 * 把雪碧图的几何关系写成 WebVTT。
 *
 * <p>播放器（原生 {@code <track kind="metadata">} 或自绘控制条）按当前悬停时间
 * 在 VTT 里查到对应的 cue，cue 的正文是一个带 {@code #xywh=} 媒体片段的 URL，
 * 指明该时间点对应雪碧图上的哪一块。这是 JW Player / Video.js 一系的既定约定，
 * 不是本项目发明的格式。
 *
 * <p><b>纯函数</b>：没有 I/O、没有依赖，因此它的测试是逐字符断言整段输出的单元测试。
 */
final class WebVttWriter {

    private WebVttWriter() {
    }

    /**
     * @param imageUrl    雪碧图的访问地址（{@code /api/assets/{id}}）
     * @param frameCount  帧数
     * @param columns     每行几块
     * @param tileWidth   单块宽（<b>从生成结果读出来的实际值</b>，不要重算）
     * @param tileHeight  单块高
     * @param totalSeconds 视频总时长
     */
    static String write(String imageUrl, int frameCount, int columns,
                        int tileWidth, int tileHeight, double totalSeconds) {

        StringBuilder vtt = new StringBuilder("WEBVTT\n");
        for (int frame = 0; frame < frameCount; frame++) {
            // 用「第 n 帧的边界 = 总时长 × n / 帧数」而不是累加步长，
            // 末帧才能正好落在总时长上，不会攒出浮点误差
            long startMillis = Math.round(totalSeconds * 1000 * frame / frameCount);
            long endMillis = Math.round(totalSeconds * 1000 * (frame + 1) / frameCount);
            int x = (frame % columns) * tileWidth;
            int y = (frame / columns) * tileHeight;

            vtt.append('\n')
                    .append(timestamp(startMillis)).append(" --> ").append(timestamp(endMillis))
                    .append('\n')
                    .append(imageUrl).append("#xywh=")
                    .append(x).append(',').append(y).append(',')
                    .append(tileWidth).append(',').append(tileHeight)
                    .append('\n');
        }
        return vtt.toString();
    }

    /** WebVTT 要求 {@code HH:MM:SS.mmm}。 */
    private static String timestamp(long millis) {
        long hours = millis / 3_600_000;
        long minutes = millis / 60_000 % 60;
        long seconds = millis / 1000 % 60;
        long remainder = millis % 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d",
                hours, minutes, seconds, remainder);
    }
}
