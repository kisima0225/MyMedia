package com.mymedia.video;

/**
 * 条目的内部结构。
 *
 * <p><b>这是独立字段，不由 {@link VideoItemType} 推导。</b>
 * 一部「电影」若实际含多个部分，同样可以是 {@code GROUPED}。
 * 扫描时按实际目录结构判定，用户可手动更改。
 */
public enum VideoStructure {
    /** 条目直接挂文件，无分组层。 */
    FLAT,
    /** 条目 → 分组（季/分册） → 文件。 */
    GROUPED
}
