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

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.ZonedDateTime;

@RestController
@RequestMapping("/api/video/stream")
class VideoStreamController {

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

    /** 用 FileChannel.transferTo 把指定区间写入响应输出流。 */
    private static StreamingResponseBody writer(VideoStreamService.StreamTarget target,
                                                long position, long count) {
        return output -> {
            try (FileChannel channel = FileChannel.open(target.path(), StandardOpenOption.READ);
                 WritableByteChannel sink = Channels.newChannel(output)) {
                long offset = position;
                long remaining = count;
                while (remaining > 0) {
                    long transferred = channel.transferTo(offset, remaining, sink);
                    if (transferred <= 0) {
                        break;
                    }
                    offset += transferred;
                    remaining -= transferred;
                }
            } catch (IOException e) {
                // 客户端拖动进度条中断连接时，响应已经无法继续写出。
            }
        };
    }
}
