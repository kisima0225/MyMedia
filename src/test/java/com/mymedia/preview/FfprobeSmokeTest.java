package com.mymedia.preview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 唯一一个真的调用 ffprobe 的测试。
 *
 * <p>ffmpeg / ffprobe 烘焙在应用镜像里，开发机上不一定装了，因此本测试用
 * {@code assumeTrue} 守门：装了就跑，没装就跳过。<b>跳过不是失败</b>——
 * 全部解析逻辑都有纯单元测试覆盖，这里验证的只是"我们拼的命令行真的能被
 * 真正的 ffprobe 接受"。
 */
class FfprobeSmokeTest {

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    @Test
    void realFfprobeAcceptsOurCommandLine(@TempDir Path tempDir) throws Exception {
        try {
            CommandResult version = runner.run(
                    List.of("ffprobe", "-version"), Duration.ofSeconds(10));
            assertThat(version.succeeded())
                    .as("ffprobe -version 失败: %s", version.stderr())
                    .isTrue();
        } catch (IOException e) {
            if (isMissingExecutable(e)) {
                assumeTrue(false, "本机没有 ffprobe，跳过真机验证（镜像里有）");
            }
            throw e;
        }

        Path fixture = tempDir.resolve("fixture.wav");
        Files.write(fixture, wavFixture());
        CommandResult result = runner.run(
                MediaCommands.probe("ffprobe", fixture),
                Duration.ofSeconds(20));

        assertThat(result.succeeded())
                .as("ffprobe 探测合法 WAV 失败: %s", result.stderr())
                .isTrue();
        FfprobeOutput output = FfprobeParser.parse(result.stdout());
        assertThat(output.durationSeconds()).isEqualTo(1);
        assertThat(output.audioCodec()).isNotBlank();
    }

    private static boolean isMissingExecutable(IOException failure) {
        Throwable cause = failure.getCause();
        if (!(cause instanceof IOException)) {
            return false;
        }
        String message = cause.getMessage();
        return message != null && message.contains("error=2");
    }

    /** 构造一秒的无声 PCM WAV，不依赖 ffmpeg 生成测试输入。 */
    private static byte[] wavFixture() {
        int sampleRate = 8_000;
        int dataSize = sampleRate;
        ByteBuffer wav = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        putAscii(wav, "RIFF");
        wav.putInt(36 + dataSize);
        putAscii(wav, "WAVE");
        putAscii(wav, "fmt ");
        wav.putInt(16);
        wav.putShort((short) 1);
        wav.putShort((short) 1);
        wav.putInt(sampleRate);
        wav.putInt(sampleRate);
        wav.putShort((short) 1);
        wav.putShort((short) 8);
        putAscii(wav, "data");
        wav.putInt(dataSize);
        return wav.array();
    }

    private static void putAscii(ByteBuffer buffer, String value) {
        buffer.put(value.getBytes(StandardCharsets.US_ASCII));
    }
}
