package com.mymedia.preview;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试用的 {@link CommandRunner} 桩。
 *
 * <p>ffmpeg / ffprobe 烘焙在应用镜像里，开发机上不一定有；本桩按命令形状作答：
 * <ul>
 *   <li>带 {@code -print_format json} 的当作探测，从 stdout 返回预置的 JSON</li>
 *   <li>其余当作出图命令，<b>在命令行最后一项（输出路径）写一张真实的小 JPEG</b>——
 *       下游的缩略图与雪碧图几何计算真的会 {@code ImageIO.read} 它，写假字节过不了</li>
 * </ul>
 *
 * <p>手写而不用 Mockito：计划 01 把 Boot 4 的 test starter 拆开引入，
 * Mockito 是否在 classpath 上没有验证过。
 */
class StubCommandRunner implements CommandRunner {

    static final String DEFAULT_PROBE_JSON = """
            {
              "streams": [
                {"codec_type": "video", "codec_name": "h264", "width": 1920, "height": 1080},
                {"codec_type": "audio", "codec_name": "aac"}
              ],
              "format": {"format_name": "mov,mp4,m4a,3gp,3g2,mj2",
                         "duration": "600.000000", "bit_rate": "2119721"}
            }
            """;

    private final List<List<String>> invocations = new ArrayList<>();

    private volatile String probeJson = DEFAULT_PROBE_JSON;
    private volatile int outputWidth = 1600;
    private volatile int outputHeight = 900;
    private volatile int exitCode = 0;

    void respondToProbeWith(String json) {
        this.probeJson = json;
    }

    void produceImageOfSize(int width, int height) {
        this.outputWidth = width;
        this.outputHeight = height;
    }

    void failWith(int exitCode) {
        this.exitCode = exitCode;
    }

    void reset() {
        probeJson = DEFAULT_PROBE_JSON;
        outputWidth = 1600;
        outputHeight = 900;
        exitCode = 0;
        invocations.clear();
    }

    List<List<String>> invocations() {
        return List.copyOf(invocations);
    }

    boolean ranCommandContaining(String fragment) {
        return invocations.stream().anyMatch(command -> command.stream().anyMatch(
                argument -> argument.contains(fragment)));
    }

    @Override
    public CommandResult run(List<String> command, Duration timeout) throws IOException {
        invocations.add(List.copyOf(command));

        if (exitCode != 0) {
            return new CommandResult(exitCode, "", "stub failure");
        }
        if (command.contains("-print_format")) {
            return new CommandResult(0, probeJson, "");
        }

        Path output = Path.of(command.get(command.size() - 1));
        Files.createDirectories(output.getParent());
        writeJpeg(output, outputWidth, outputHeight);
        return new CommandResult(0, "", "frame= 1 stub");
    }

    private static void writeJpeg(Path output, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.DARK_GRAY);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        if (!ImageIO.write(image, "jpg", output.toFile())) {
            throw new IOException("当前 JDK 没有 JPEG 编码器，测试无法继续");
        }
    }
}
