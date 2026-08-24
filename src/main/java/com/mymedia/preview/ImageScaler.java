package com.mymedia.preview;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 等比缩放并写出 JPEG。
 *
 * <p>用 JDK 自带的 {@code javax.imageio}，不引 Thumbnailator / imgscalr——
 * 本项目只需要"等比缩到指定宽度"这一个操作，为它加一个依赖说不出理由。
 * 预览解码边界由当前 JDK 注册的 {@code ImageIO} reader 决定；扫描与归档索引可以
 * 收录更多可原样提供给阅读器的扩展名，但这不表示它们都能生成预览。没有对应
 * reader 时 {@link #read(InputStream)} 会抛出 {@link IOException}，不会产生伪预览。
 *
 * <p>Spring Boot 默认设置 {@code java.awt.headless=true}，
 * {@link BufferedImage} 与 {@link Graphics2D} 在无显示环境下工作正常。
 */
final class ImageScaler {

    /** 缩放结果的实际尺寸。 */
    record Size(int width, int height) {
    }

    private ImageScaler() {
    }

    static BufferedImage read(Path source) throws IOException {
        try (InputStream in = Files.newInputStream(source)) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                throw new IOException("无法识别的图片格式: " + source);
            }
            return image;
        }
    }

    static BufferedImage read(InputStream in) throws IOException {
        BufferedImage image = ImageIO.read(in);
        if (image == null) {
            throw new IOException("无法识别的图片格式");
        }
        return image;
    }

    /**
     * 等比缩到目标宽度并写成 JPEG，返回实际尺寸。
     *
     * <p>源图比目标还窄时不放大——放大只会让文件变大、观感变糊。
     */
    static Size writeJpeg(BufferedImage source, int targetWidth, Path output) throws IOException {
        int width = Math.min(targetWidth, source.getWidth());
        int height = Math.max(1, Math.round(source.getHeight() * (float) width / source.getWidth()));

        // TYPE_INT_RGB：JPEG 没有 alpha 通道，带透明度的源图直接写会得到偏色的结果
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();

        Files.createDirectories(output.getParent());
        if (!ImageIO.write(scaled, "jpg", output.toFile())) {
            throw new IOException("当前运行时没有 JPEG 编码器");
        }
        return new Size(width, height);
    }
}
