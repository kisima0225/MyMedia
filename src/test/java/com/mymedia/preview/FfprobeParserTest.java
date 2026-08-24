package com.mymedia.preview;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FfprobeParserTest {

    private static final String TYPICAL_MP4 = """
            {
              "streams": [
                {
                  "index": 0,
                  "codec_name": "h264",
                  "codec_type": "video",
                  "width": 1920,
                  "height": 1080,
                  "duration": "596.474000",
                  "bit_rate": "1970198"
                },
                {
                  "index": 1,
                  "codec_name": "aac",
                  "codec_type": "audio",
                  "sample_rate": "48000",
                  "channels": 2
                }
              ],
              "format": {
                "filename": "/media/movies/BigBuckBunny.mp4",
                "nb_streams": 2,
                "format_name": "mov,mp4,m4a,3gp,3g2,mj2",
                "duration": "596.474000",
                "size": "158008374",
                "bit_rate": "2119721"
              }
            }
            """;

    @Test
    void readsDurationRoundedToSeconds() {
        assertThat(FfprobeParser.parse(TYPICAL_MP4).durationSeconds()).isEqualTo(596);
    }

    @Test
    void readsGeometryAndCodecsFromTheRightStreams() {
        FfprobeOutput output = FfprobeParser.parse(TYPICAL_MP4);

        assertThat(output.width()).isEqualTo(1920);
        assertThat(output.height()).isEqualTo(1080);
        assertThat(output.videoCodec()).isEqualTo("h264");
        assertThat(output.audioCodec()).isEqualTo("aac");
    }

    @Test
    void takesFirstTokenOfFormatNameAsContainer() {
        // format_name 是一串同义容器名，取第一个作为展示值
        assertThat(FfprobeParser.parse(TYPICAL_MP4).container()).isEqualTo("mov");
    }

    @Test
    void prefersFormatBitrateOverStreamBitrate() {
        assertThat(FfprobeParser.parse(TYPICAL_MP4).bitrate()).isEqualTo(2119721L);
    }

    @Test
    void keepsRawJsonForLaterInspection() {
        assertThat(FfprobeParser.parse(TYPICAL_MP4).rawJson()).contains("BigBuckBunny.mp4");
    }

    @Test
    void fallsBackToVideoStreamDurationWhenFormatHasNone() {
        String noFormatDuration = """
                {
                  "streams": [
                    {"codec_type": "video", "codec_name": "vp9", "width": 640, "height": 360,
                     "duration": "12.500000"}
                  ],
                  "format": {"format_name": "matroska,webm"}
                }
                """;

        FfprobeOutput output = FfprobeParser.parse(noFormatDuration);

        assertThat(output.durationSeconds()).isEqualTo(13);
        assertThat(output.container()).isEqualTo("matroska");
        assertThat(output.bitrate()).isNull();
    }

    @Test
    void audioOnlyFileHasNoGeometryButStillParses() {
        String audioOnly = """
                {
                  "streams": [{"codec_type": "audio", "codec_name": "flac"}],
                  "format": {"format_name": "flac", "duration": "180.000000"}
                }
                """;

        FfprobeOutput output = FfprobeParser.parse(audioOnly);

        assertThat(output.width()).isNull();
        assertThat(output.height()).isNull();
        assertThat(output.videoCodec()).isNull();
        assertThat(output.audioCodec()).isEqualTo("flac");
        assertThat(output.durationSeconds()).isEqualTo(180);
    }

    @Test
    void unparseableOutputRaisesInsteadOfSilentlyReturningNulls() {
        // 静默返回空值会让上游以为探测成功，进而写一堆 null 进 video_file
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> FfprobeParser.parse("not json at all"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ffprobe");
    }

    @Test
    void durationOfNAIsTreatedAsUnknown() {
        // 部分流式容器会给出字面量 "N/A"
        String naDuration = """
                {
                  "streams": [{"codec_type": "video", "codec_name": "h264", "width": 4, "height": 4}],
                  "format": {"format_name": "mpegts", "duration": "N/A"}
                }
                """;

        assertThat(FfprobeParser.parse(naDuration).durationSeconds()).isNull();
    }
}
