package com.mymedia.video.range;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RangeParserTest {

    private static final long LENGTH = 1000L;

    @Test
    void nullHeaderMeansFullContent() {
        assertThat(RangeParser.resolve(null, LENGTH))
                .isEqualTo(new RangeResolution.Full(LENGTH));
    }

    @Test
    void blankHeaderMeansFullContent() {
        assertThat(RangeParser.resolve("   ", LENGTH))
                .isEqualTo(new RangeResolution.Full(LENGTH));
    }

    @Test
    void closedRange() {
        assertThat(RangeParser.resolve("bytes=0-499", LENGTH))
                .isEqualTo(new RangeResolution.Partial(0, 499, LENGTH));
    }

    @Test
    void openEndedRangeExtendsToEnd() {
        assertThat(RangeParser.resolve("bytes=500-", LENGTH))
                .isEqualTo(new RangeResolution.Partial(500, 999, LENGTH));
    }

    @Test
    void suffixRangeCountsFromEnd() {
        // bytes=-500 表示"最后 500 字节"，不是"从 0 到 500"
        assertThat(RangeParser.resolve("bytes=-500", LENGTH))
                .isEqualTo(new RangeResolution.Partial(500, 999, LENGTH));
    }

    @Test
    void suffixLongerThanFileClampsToWholeFile() {
        assertThat(RangeParser.resolve("bytes=-5000", LENGTH))
                .isEqualTo(new RangeResolution.Partial(0, 999, LENGTH));
    }

    @Test
    void endBeyondFileIsClamped() {
        assertThat(RangeParser.resolve("bytes=900-5000", LENGTH))
                .isEqualTo(new RangeResolution.Partial(900, 999, LENGTH));
    }

    @Test
    void startBeyondLongRangeIsUnsatisfiable() {
        assertThat(RangeParser.resolve("bytes=9223372036854775808-", LENGTH))
                .isEqualTo(new RangeResolution.Unsatisfiable(LENGTH));
    }

    @Test
    void endBeyondLongRangeIsClamped() {
        assertThat(RangeParser.resolve("bytes=0-9223372036854775808", LENGTH))
                .isEqualTo(new RangeResolution.Partial(0, 999, LENGTH));
    }

    @Test
    void suffixBeyondLongRangeCoversWholeFile() {
        assertThat(RangeParser.resolve("bytes=-9223372036854775808", LENGTH))
                .isEqualTo(new RangeResolution.Partial(0, 999, LENGTH));
    }

    @Test
    void startAtOrBeyondLengthIsUnsatisfiable() {
        assertThat(RangeParser.resolve("bytes=1000-", LENGTH))
                .isEqualTo(new RangeResolution.Unsatisfiable(LENGTH));
        assertThat(RangeParser.resolve("bytes=5000-6000", LENGTH))
                .isEqualTo(new RangeResolution.Unsatisfiable(LENGTH));
    }

    @Test
    void malformedEndIsFullEvenWhenStartIsOutOfRange() {
        assertThat(RangeParser.resolve("bytes=1000-abc", LENGTH))
                .isEqualTo(new RangeResolution.Full(LENGTH));
    }

    @Test
    void startGreaterThanEndIsUnsatisfiable() {
        assertThat(RangeParser.resolve("bytes=500-100", LENGTH))
                .isEqualTo(new RangeResolution.Unsatisfiable(LENGTH));
    }

    @Test
    void multipleRangesReturnTheirUnion() {
        // spec 7.3 的决策：不实现 multipart/byteranges，返回覆盖全部区间的并集。
        // 浏览器的 <video> 元素不会发送多重 Range。
        assertThat(RangeParser.resolve("bytes=0-99,200-299", LENGTH))
                .isEqualTo(new RangeResolution.Partial(0, 299, LENGTH));
    }

    @Test
    void unsupportedUnitIsIgnored() {
        // RFC 9110：无法识别的 range unit 应当忽略 Range 头，返回完整内容
        assertThat(RangeParser.resolve("items=0-10", LENGTH))
                .isEqualTo(new RangeResolution.Full(LENGTH));
    }

    @Test
    void malformedHeaderIsIgnored() {
        assertThat(RangeParser.resolve("bytes=abc-def", LENGTH))
                .isEqualTo(new RangeResolution.Full(LENGTH));
        assertThat(RangeParser.resolve("bytes=", LENGTH))
                .isEqualTo(new RangeResolution.Full(LENGTH));
        assertThat(RangeParser.resolve("bytes=-", LENGTH))
                .isEqualTo(new RangeResolution.Full(LENGTH));
    }

    @Test
    void signedRangeNumbersAreMalformed() {
        assertThat(RangeParser.resolve("bytes=+0-1", LENGTH))
                .isEqualTo(new RangeResolution.Full(LENGTH));
        assertThat(RangeParser.resolve("bytes=0-+1", LENGTH))
                .isEqualTo(new RangeResolution.Full(LENGTH));
    }

    @Test
    void multipleDashRangeIsMalformed() {
        assertThat(RangeParser.resolve("bytes=0--1", LENGTH))
                .isEqualTo(new RangeResolution.Full(LENGTH));
    }

    @Test
    void whitespaceInsideUnitIsNotIgnored() {
        assertThat(RangeParser.resolve("b y t e s=0-1", LENGTH))
                .isEqualTo(new RangeResolution.Full(LENGTH));
    }

    @Test
    void emptyRangePartsAreMalformed() {
        assertThat(RangeParser.resolve("bytes=0-99,", LENGTH))
                .isEqualTo(new RangeResolution.Full(LENGTH));
        assertThat(RangeParser.resolve("bytes=,0-99", LENGTH))
                .isEqualTo(new RangeResolution.Full(LENGTH));
        assertThat(RangeParser.resolve("bytes=0-99,,200-299", LENGTH))
                .isEqualTo(new RangeResolution.Full(LENGTH));
    }

    @Test
    void zeroLengthFileIsAlwaysUnsatisfiableForAnyRange() {
        assertThat(RangeParser.resolve("bytes=0-", 0))
                .isEqualTo(new RangeResolution.Unsatisfiable(0));
    }

    @Test
    void singleByteRange() {
        assertThat(RangeParser.resolve("bytes=0-0", LENGTH))
                .isEqualTo(new RangeResolution.Partial(0, 0, LENGTH));
    }

    @Test
    void whitespaceIsTolerated() {
        assertThat(RangeParser.resolve("bytes = 0 - 499 ", LENGTH))
                .isEqualTo(new RangeResolution.Partial(0, 499, LENGTH));
    }

    @Test
    void contentLengthOfPartialIsInclusive() {
        RangeResolution.Partial partial =
                (RangeResolution.Partial) RangeParser.resolve("bytes=0-499", LENGTH);

        // Range 的两端都是闭区间，长度是 end - start + 1
        assertThat(partial.contentLength()).isEqualTo(500L);
    }

    @Test
    void negativeFileLengthIsRejected() {
        assertThatThrownBy(() -> RangeParser.resolve(null, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
