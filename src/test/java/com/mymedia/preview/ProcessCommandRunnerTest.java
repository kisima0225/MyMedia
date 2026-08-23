package com.mymedia.preview;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessCommandRunnerTest {

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    /** 当前 JVM 的 java 可执行文件，跨平台可用。 */
    private static String javaBinary() {
        return ProcessHandle.current().info().command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    private static List<String> sleepFor(long millis) {
        return List.of(javaBinary(), "-cp", System.getProperty("java.class.path"),
                SleepingProcess.class.getName(), String.valueOf(millis));
    }

    @Test
    void capturesStdoutAndExitCode() throws Exception {
        CommandResult result = runner.run(sleepFor(0), Duration.ofSeconds(30));

        assertThat(result.exitCode()).isZero();
        assertThat(result.succeeded()).isTrue();
        assertThat(result.stdout()).contains("finished");
    }

    @Test
    void capturesNonZeroExitWithoutThrowing() throws Exception {
        // 缺少参数 → SleepingProcess 抛异常退出，退出码非 0，stderr 有栈
        List<String> command = List.of(javaBinary(), "-cp", System.getProperty("java.class.path"),
                SleepingProcess.class.getName());

        CommandResult result = runner.run(command, Duration.ofSeconds(30));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.stderr()).contains("ArrayIndexOutOfBoundsException");
    }

    @Test
    void killsProcessThatOutlivesItsTimeout() {
        // 没有超时的话，一个卡死的 ffmpeg 会永远占住一个任务租约
        assertThatThrownBy(() -> runner.run(sleepFor(60_000), Duration.ofMillis(500)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("超时");
    }

    @Test
    void missingBinaryFailsFastWithTheCommandInTheMessage() {
        assertThatThrownBy(() -> runner.run(
                List.of("definitely-not-a-real-binary-xyz"), Duration.ofSeconds(5)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("definitely-not-a-real-binary-xyz");
    }
}