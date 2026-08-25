package com.mymedia.upload;

import java.util.Locale;

/**
 * 上传文件名的净化。
 *
 * <p><b>这是安全边界，不是顺手清理。</b>净化后的名字会被拼进媒体库根目录下的
 * 文件系统路径，一个没处理干净的 {@code ../} 就是一次任意写。
 *
 * <p>处理的每一条都对应一个真实问题：
 * <ul>
 *   <li>目录分隔符（两种）—— 路径穿越</li>
 *   <li>纯点名 {@code .} / {@code ..} —— 同上</li>
 *   <li>控制字符 —— 日志注入与不可见的文件名</li>
 *   <li>{@code <>:"|?*} —— Windows 上根本创建不了</li>
 *   <li>结尾的点与空格 —— Windows <b>静默吃掉</b>，导致写进去的名字与读出来的对不上，
 *       下一次扫描会把它当成另一个文件</li>
 *   <li>超长 —— 多数文件系统的单段上限是 255 字节，中文按 UTF-8 是 3 字节/字</li>
 * </ul>
 */
final class SafeFileName {

    /** 200 个字符：中文按 UTF-8 最多 600 字节，仍在 ext4/NTFS 的 255 字节上限内？
     *  不在——所以这里限的是字符数并且留足余量，真正的兜底是文件系统会报错。
     *  取 200 是为了让「明显过长」的名字在进入文件系统之前就被截断。 */
    private static final int MAX_LENGTH = 200;

    private static final String FORBIDDEN = "<>:\"|?*";

    private SafeFileName() {
    }

    static String of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        // 两种分隔符都切：客户端可能是 Windows，服务端可能是 Linux
        String name = raw;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }

        StringBuilder cleaned = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            if (c < 0x20 || c == 0x7F || FORBIDDEN.indexOf(c) >= 0) {
                continue;
            }
            cleaned.append(c);
        }

        // Windows 会静默吃掉结尾的点和空格
        String trimmed = cleaned.toString().strip();
        while (trimmed.endsWith(".") || trimmed.endsWith(" ")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
        }

        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("文件名净化后为空: " + raw);
        }
        return truncate(trimmed);
    }

    /** 截断时保留扩展名——扩展名决定媒体类型判定，丢了它文件就进不了库。 */
    private static String truncate(String name) {
        if (name.length() <= MAX_LENGTH) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String extension = (dot > 0 && name.length() - dot <= 12)
                ? name.substring(dot).toLowerCase(Locale.ROOT)
                : "";
        return name.substring(0, MAX_LENGTH - extension.length()) + extension;
    }
}
