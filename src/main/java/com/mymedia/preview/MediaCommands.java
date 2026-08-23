package com.mymedia.preview;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * ffmpeg / ffprobe 的命令行构造。
 *
 * <p>单独成类是为了让参数顺序可以被单元测试断言——{@code -ss} 放在 {@code -i}
 * 前还是后是性能差几十倍的事，而这种错误在集成测试里根本看不出来（两种写法
 * 都能出图）。
 */
final class MediaCommands {

    /** 抽帧点取时长的十分之一：避开片头黑屏与厂标，又不至于剧透。 */
    private static final double COVER_POSITION_RATIO = 0.1;

    private MediaCommands() {
    }

    static List<String> probe(String ffprobePath, Path input) {
        return List.of(ffprobePath,
                "-v", "quiet",
                "-print_format", "json",
                "-show_format", "-show_streams",
                input.toString());
    }

    /**
     * 抽一帧做封面。
     *
     * <p>{@code -ss} 必须在 {@code -i} <b>之前</b>：这时 ffmpeg 直接 seek 到最近的
     * 关键帧再开始解码；放在之后则是先从头解码、再丢弃前面的帧，一部两小时的
     * 电影要多花几十秒。
     */
    static List<String> coverFrame(String ffmpegPath, Path input, Integer durationSeconds,
                                   int width, Path output) {
        double position = durationSeconds == null ? 0.0 : durationSeconds * COVER_POSITION_RATIO;
        return List.of(ffmpegPath,
                "-y",
                "-ss", String.format(Locale.ROOT, "%.3f", position),
                "-i", input.toString(),
                "-frames:v", "1",
                "-vf", "scale=" + width + ":-2",
                "-q:v", "3",
                output.toString());
    }

    /**
     * 生成进度条预览用的雪碧图。
     *
     * <p>帧数固定（默认 100）、网格固定（10 × 10），于是<b>永远只有一张图、一个 VTT</b>，
     * 省掉多图分页的全部复杂度。抽帧间隔由 {@code fps = frames / duration} 反推，
     * 短片会得到大于 1 的 fps，长片会得到很小的 fps，两端都成立。
     *
     * <p>{@code scale=160:-2} 里的 {@code -2} 表示"高度按比例算，并取到 2 的倍数"——
     * 大多数编码器要求偶数尺寸。
     */
    static List<String> spriteSheet(String ffmpegPath, Path input, int durationSeconds,
                                    int frames, int columns, int tileWidth, Path output) {
        int rows = (int) Math.ceil((double) frames / columns);
        double fps = (double) frames / Math.max(durationSeconds, 1);
        String filter = String.format(Locale.ROOT, "fps=%.6f,scale=%d:-2,tile=%dx%d",
                fps, tileWidth, columns, rows);
        return List.of(ffmpegPath,
                "-y",
                "-i", input.toString(),
                "-vf", filter,
                "-frames:v", "1",
                output.toString());
    }
}