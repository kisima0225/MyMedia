package com.mymedia.video.range;

import java.math.BigInteger;

/**
 * HTTP Range 头解析，遵循 RFC 9110 §14.1。
 *
 * <p>三条容易写错的规则：
 * <ul>
 *   <li>{@code bytes=-500} 是「<b>最后</b> 500 字节」，不是「从 0 到 500」</li>
 *   <li>Range 两端都是<b>闭区间</b>，长度是 {@code end - start + 1}</li>
 *   <li>语法错误或不认识的 unit 应当<b>忽略 Range 头返回完整内容</b>，
 *       而不是报错——只有语法正确但区间越界才返回 416</li>
 * </ul>
 *
 * <p>多重 Range 按 spec 7.3 的决策返回<b>并集</b>（最小起点到最大终点），
 * 不实现 {@code multipart/byteranges}。浏览器的 {@code <video>} 元素不发送多重 Range。
 */
public final class RangeParser {

    private static final String UNIT_PREFIX = "bytes";
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;

    private RangeParser() {
    }

    public static RangeResolution resolve(String rangeHeader, long fileLength) {
        if (fileLength < 0) {
            throw new IllegalArgumentException("fileLength must not be negative");
        }
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return new RangeResolution.Full(fileLength);
        }

        String header = normalizeDelimiterWhitespace(rangeHeader);
        int equals = header.indexOf('=');
        if (equals < 0 || equals != header.lastIndexOf('=')) {
            return new RangeResolution.Full(fileLength);
        }

        String unit = header.substring(0, equals);
        if (!UNIT_PREFIX.equalsIgnoreCase(unit)) {
            // RFC 9110：无法识别的 unit 必须忽略整个 Range 头
            return new RangeResolution.Full(fileLength);
        }

        String spec = header.substring(equals + 1);
        if (spec.isEmpty()) {
            return new RangeResolution.Full(fileLength);
        }

        long unionStart = Long.MAX_VALUE;
        long unionEnd = Long.MIN_VALUE;
        boolean anyValid = false;
        boolean anyUnsatisfiable = false;

        for (String part : spec.split(",", -1)) {
            long[] resolved = resolveSingle(part, fileLength);
            if (resolved == null) {
                // 语法错误：整个 Range 头作废，返回完整内容
                return new RangeResolution.Full(fileLength);
            }
            if (resolved.length == 0) {
                anyUnsatisfiable = true;
                continue;
            }
            anyValid = true;
            unionStart = Math.min(unionStart, resolved[0]);
            unionEnd = Math.max(unionEnd, resolved[1]);
        }

        if (!anyValid) {
            return anyUnsatisfiable
                    ? new RangeResolution.Unsatisfiable(fileLength)
                    : new RangeResolution.Full(fileLength);
        }
        return new RangeResolution.Partial(unionStart, unionEnd, fileLength);
    }

    /**
     * @return {@code null} 表示语法错误；长度为 0 的数组表示语法正确但不可满足；
     *         否则为 {@code [start, endInclusive]}
     */
    private static long[] resolveSingle(String part, long fileLength) {
        int dash = part.indexOf('-');
        if (dash < 0 || dash != part.lastIndexOf('-')) {
            return null;
        }
        String startText = part.substring(0, dash);
        String endText = part.substring(dash + 1);

        if (startText.isEmpty() && endText.isEmpty()) {
            return null;
        }
        if ((!startText.isEmpty() && !isDigits(startText))
                || (!endText.isEmpty() && !isDigits(endText))) {
            return null;
        }

        BigInteger fileLengthValue = BigInteger.valueOf(fileLength);
        if (startText.isEmpty()) {
            // 后缀形式 bytes=-N：最后 N 个字节
            BigInteger suffixLength = new BigInteger(endText);
            if (suffixLength.signum() <= 0 || fileLength == 0) {
                return new long[0];
            }
            BigInteger start = fileLengthValue.subtract(suffixLength).max(ZERO);
            return new long[]{start.longValueExact(), fileLength - 1};
        }

        BigInteger start = new BigInteger(startText);
        BigInteger end = endText.isEmpty()
                ? fileLengthValue.subtract(ONE)
                : new BigInteger(endText);
        // 语法已经验证后才判定边界，避免 bytes=1000-abc 被误判为 416。
        if (start.compareTo(fileLengthValue) >= 0 || end.compareTo(start) < 0) {
            return new long[0];
        }

        BigInteger lastByte = fileLengthValue.subtract(ONE);
        if (end.compareTo(lastByte) > 0) {
            end = lastByte;
        }
        return new long[]{start.longValueExact(), end.longValueExact()};
    }

    private static boolean isDigits(String text) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return !text.isEmpty();
    }

    private static String normalizeDelimiterWhitespace(String header) {
        StringBuilder normalized = new StringBuilder(header.length());
        for (int index = 0; index < header.length(); index++) {
            char character = header.charAt(index);
            if (!Character.isWhitespace(character)) {
                normalized.append(character);
                continue;
            }

            int previous = index - 1;
            while (previous >= 0 && Character.isWhitespace(header.charAt(previous))) {
                previous--;
            }
            int next = index + 1;
            while (next < header.length() && Character.isWhitespace(header.charAt(next))) {
                next++;
            }
            if (previous < 0 || next == header.length()
                    || isDelimiter(header.charAt(previous))
                    || isDelimiter(header.charAt(next))) {
                continue;
            }
            normalized.append(character);
        }
        return normalized.toString();
    }

    private static boolean isDelimiter(char character) {
        return character == '=' || character == '-' || character == ',';
    }
}
