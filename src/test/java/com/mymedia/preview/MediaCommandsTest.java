package com.mymedia.preview;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MediaCommandsTest {

    private static final Path INPUT = Path.of("/media", "a.mkv");
    private static final Path OUTPUT = Path.of("/derived", "a.jpg");

    @Test
    void probeAsksForJsonWithFormatAndStreams() {
        assertThat(MediaCommands.probe("ffprobe", INPUT)).containsExactly(
                "ffprobe", "-v", "quiet", "-print_format", "json",
                "-show_format", "-show_streams", INPUT.toString());
    }

    @Test
    void coverFrameSeeksBeforeInputForFastSeeking() {
        List<String> command = MediaCommands.coverFrame("ffmpeg", INPUT, 600, 640, OUTPUT);

        // -ss 放在 -i 前面走的是关键帧快速定位；放后面会让 ffmpeg 从头解码到该点，
        // 一部两小时的电影能慢上几十秒。顺序不是风格问题。
        assertThat(command.indexOf("-ss")).isLessThan(command.indexOf("-i"));
        assertThat(command).containsExactly(
                "ffmpeg", "-y", "-ss", "60.000", "-i", INPUT.toString(),
                "-frames:v", "1", "-vf", "scale=640:-2", "-q:v", "3", OUTPUT.toString());
    }

    @Test
    void coverFrameOfVeryShortVideoStartsAtZero() {
        List<String> command = MediaCommands.coverFrame("ffmpeg", INPUT, 3, 640, OUTPUT);

        assertThat(command).contains("0.300");
    }

    @Test
    void coverFrameOfUnknownDurationStartsAtZero() {
        List<String> command = MediaCommands.coverFrame("ffmpeg", INPUT, null, 640, OUTPUT);

        assertThat(command.get(command.indexOf("-ss") + 1)).isEqualTo("0.000");
    }

    @Test
    void spriteSheetUsesFpsThatYieldsExactlyTheRequestedFrameCount() {
        // 100 帧 / 600 秒 = 每 6 秒一帧
        List<String> command = MediaCommands.spriteSheet("ffmpeg", INPUT, 600, 100, 10, 160, OUTPUT);

        assertThat(command).containsExactly(
                "ffmpeg", "-y", "-i", INPUT.toString(),
                "-vf", "fps=0.166667,scale=160:-2,tile=10x10",
                "-frames:v", "1", OUTPUT.toString());
    }

    @Test
    void spriteSheetOfShortVideoStillProducesOneSheet() {
        List<String> command = MediaCommands.spriteSheet("ffmpeg", INPUT, 20, 100, 10, 160, OUTPUT);

        assertThat(command).contains("fps=5.000000,scale=160:-2,tile=10x10");
    }

    @Test
    void binaryPathIsConfigurableForContainersThatShipItElsewhere() {
        assertThat(MediaCommands.probe("/usr/local/bin/ffprobe", INPUT))
                .first().isEqualTo("/usr/local/bin/ffprobe");
    }
}
