package com.mymedia.video.web;

import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoStreamService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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

        return VideoRangeResponder.respond(
                streamService.locate(userId, fileId), rangeHeader, ifRange);
    }
}
