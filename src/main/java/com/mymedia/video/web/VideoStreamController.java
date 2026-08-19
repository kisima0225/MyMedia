package com.mymedia.video.web;

import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoStreamService;
import com.mymedia.video.range.RangeParser;
import com.mymedia.video.range.RangeResolution;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.ZonedDateTime;

@RestController
@RequestMapping("/api/video/stream")
class VideoStreamController {

    private static final int MAX_ZERO_TRANSFER_ATTEMPTS = 3;
    private static final int DIRECT_BUFFER_SIZE = 16 * 1024;

    private final VideoStreamService streamService;
    private final UserQueryService userQueryService;

    VideoStreamController(VideoStreamService streamService, UserQueryService userQueryService) {
        this.streamService = streamService;
        this.userQueryService = userQueryService;
    }

    @GetMapping("/{fileId}")
    ResponseEntity<StreamingResponseBody> stream(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long fileId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            @RequestHeader(value = HttpHeaders.IF_RANGE, required = false) String ifRange) {

        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
        VideoStreamService.StreamTarget target = streamService.locate(userId, fileId);

        String effectiveRange = ifRangeMatches(ifRange, target) ? rangeHeader : null;
        RangeResolution resolution = RangeParser.resolve(effectiveRange, target.sizeBytes());

        return switch (resolution) {
            case RangeResolution.Full full -> ResponseEntity.ok()
                    .headers(commonHeaders(target))
                    .contentLength(full.length())
                    .body(writer(target, 0, full.length()));

            case RangeResolution.Partial partial -> ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .headers(commonHeaders(target))
                    .header(HttpHeaders.CONTENT_RANGE, partial.contentRangeHeader())
                    .contentLength(partial.contentLength())
                    .body(writer(target, partial.start(), partial.contentLength()));

            case RangeResolution.Unsatisfiable unsatisfiable -> ResponseEntity
                    .status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .headers(commonHeaders(target))
                    .header(HttpHeaders.CONTENT_RANGE, unsatisfiable.contentRangeHeader())
                    .contentLength(0)
                    .build();
        };
    }

    private static HttpHeaders commonHeaders(VideoStreamService.StreamTarget target) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setETag(target.etag());
        headers.set(HttpHeaders.CONTENT_TYPE, target.contentType());
        return headers;
    }

    /**
     * If-Range 只允许强 ETag 或合法 HTTP-date。日期条件是资源没有晚于该日期
     * 修改时才可以使用 Range；不认识的值一律回退到完整响应。
     */
    private static boolean ifRangeMatches(String ifRange, VideoStreamService.StreamTarget target) {
        if (ifRange == null) {
            return true;
        }

        String value = ifRange.trim();
        if (value.equals(target.etag())) {
            return true;
        }

        try {
            Instant date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return target.lastModified() != null && !target.lastModified().isAfter(date);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * 优先用 FileChannel.transferTo 写出；底层实现持续零进度时才切换到
     * 有界 direct buffer，避免在声明的 Content-Length 下静默短写。
     */
    private static StreamingResponseBody writer(VideoStreamService.StreamTarget target,
                                                long position, long count) {
        return output -> {
            try (FileChannel channel = FileChannel.open(target.path(), StandardOpenOption.READ);
                 WritableByteChannel sink = Channels.newChannel(output)) {
                transferRange(channel, sink, target.path(), position, count);
            }
        };
    }

    private static void transferRange(FileChannel source, WritableByteChannel sink,
                                      Path path, long position, long count)
            throws IOException {
        long offset = position;
        long remaining = count;
        int zeroTransferAttempts = 0;

        while (remaining > 0) {
            long transferred = source.transferTo(offset, remaining, sink);
            if (transferred > 0) {
                offset += transferred;
                remaining -= transferred;
                zeroTransferAttempts = 0;
                continue;
            }

            zeroTransferAttempts++;
            if (zeroTransferAttempts < MAX_ZERO_TRANSFER_ATTEMPTS) {
                continue;
            }
            transferWithDirectBuffer(source, sink, path, offset, remaining);
            return;
        }
    }

    private static void transferWithDirectBuffer(FileChannel source, WritableByteChannel sink,
                                                 Path path, long position, long count)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocateDirect(
                (int) Math.min(DIRECT_BUFFER_SIZE, count));
        long offset = position;
        long remaining = count;

        while (remaining > 0) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), remaining));
            int read = source.read(buffer, offset);
            if (read < 0) {
                throw new EOFException("视频文件提前结束: " + path
                        + "，仍有 " + remaining + " 字节未写出");
            }
            if (read == 0) {
                throw new IOException("读取视频文件时无进展: " + path);
            }

            buffer.flip();
            writeBuffer(sink, buffer, path);
            offset += read;
            remaining -= read;
        }
    }

    private static void writeBuffer(WritableByteChannel sink, ByteBuffer buffer,
                                    Path path) throws IOException {
        int zeroWriteAttempts = 0;
        while (buffer.hasRemaining()) {
            int written = sink.write(buffer);
            if (written < 0) {
                throw new EOFException("响应输出流提前关闭: " + path);
            }
            if (written == 0) {
                zeroWriteAttempts++;
                if (zeroWriteAttempts >= MAX_ZERO_TRANSFER_ATTEMPTS) {
                    throw new IOException("写出视频响应时无进展: " + path);
                }
                continue;
            }
            zeroWriteAttempts = 0;
        }
    }
}
