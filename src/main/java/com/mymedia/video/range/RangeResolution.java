package com.mymedia.video.range;

/**
 * Range 头的解析结果，三种互斥情形。
 *
 * <p>用 sealed interface 而非「返回可能为 null 的 Range 对象」：
 * 调用方在 switch 上必须穷尽三种情形，漏掉任何一种都编译不过。
 */
public sealed interface RangeResolution {

    /** 无 Range 头、或 Range 头无法识别 —— 返回 200 与完整内容。 */
    record Full(long length) implements RangeResolution {
    }

    /** 有效的部分请求 —— 返回 206。两端均为闭区间。 */
    record Partial(long start, long endInclusive, long totalLength) implements RangeResolution {

        /** 闭区间的长度是 end - start + 1。 */
        public long contentLength() {
            return endInclusive - start + 1;
        }

        /** {@code Content-Range} 响应头的值。 */
        public String contentRangeHeader() {
            return "bytes " + start + "-" + endInclusive + "/" + totalLength;
        }
    }

    /** 请求的区间落在文件之外 —— 返回 416。 */
    record Unsatisfiable(long totalLength) implements RangeResolution {

        /** 416 响应必须带 {@code Content-Range: bytes} 星号斜杠长度。 */
        public String contentRangeHeader() {
            return "bytes */" + totalLength;
        }
    }
}
