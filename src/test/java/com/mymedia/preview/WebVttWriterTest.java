package com.mymedia.preview;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebVttWriterTest {

    @Test
    void writesOneCuePerFrameInRowMajorOrder() {
        String vtt = WebVttWriter.write("/api/assets/7", 4, 2, 100, 50, 8.0);

        assertThat(vtt).isEqualTo("""
                WEBVTT

                00:00:00.000 --> 00:00:02.000
                /api/assets/7#xywh=0,0,100,50

                00:00:02.000 --> 00:00:04.000
                /api/assets/7#xywh=100,0,100,50

                00:00:04.000 --> 00:00:06.000
                /api/assets/7#xywh=0,50,100,50

                00:00:06.000 --> 00:00:08.000
                /api/assets/7#xywh=100,50,100,50
                """);
    }

    @Test
    void lastCueEndsExactlyAtTotalDuration() {
        // 100 帧除 3601 秒除不尽，末帧不能因为累加误差而超出或不足
        String vtt = WebVttWriter.write("/api/assets/1", 100, 10, 160, 90, 3601.0);

        assertThat(vtt).contains("--> 01:00:01.000");
        assertThat(vtt.lines().filter(line -> line.contains("-->"))).hasSize(100);
    }

    @Test
    void formatsTimestampsWithHoursMinutesSecondsMillis() {
        String vtt = WebVttWriter.write("/api/assets/1", 2, 2, 10, 10, 7200.0);

        assertThat(vtt).contains("00:00:00.000 --> 01:00:00.000");
        assertThat(vtt).contains("01:00:00.000 --> 02:00:00.000");
    }

    @Test
    void singleFrameStillProducesAValidFile() {
        String vtt = WebVttWriter.write("/api/assets/1", 1, 10, 160, 90, 5.0);

        assertThat(vtt).startsWith("WEBVTT\n\n");
        assertThat(vtt).contains("00:00:00.000 --> 00:00:05.000");
        assertThat(vtt).contains("#xywh=0,0,160,90");
    }
}
