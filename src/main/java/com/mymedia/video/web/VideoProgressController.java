package com.mymedia.video.web;

import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoProgress;
import com.mymedia.video.VideoProgressService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/video")
class VideoProgressController {

    private final VideoProgressService progressService;
    private final UserQueryService userQueryService;

    VideoProgressController(VideoProgressService progressService,
                            UserQueryService userQueryService) {
        this.progressService = progressService;
        this.userQueryService = userQueryService;
    }

    @PutMapping("/progress/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void record(@AuthenticationPrincipal UserDetails principal,
                @PathVariable Long fileId,
                @Valid @RequestBody ProgressRequest request) {
        progressService.record(currentUserId(principal), fileId,
                request.positionSeconds(), request.durationSeconds());
    }

    @GetMapping("/continue-watching")
    List<VideoProgressService.ContinueWatchingEntry> continueWatching(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(defaultValue = "20") int limit) {
        return progressService.continueWatching(currentUserId(principal), limit);
    }

    private Long currentUserId(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
    }

    record ProgressRequest(@Min(0) int positionSeconds, Integer durationSeconds) {
    }

    record ProgressResponse(Long fileId, int positionSeconds, Integer durationSeconds, boolean completed) {

        static ProgressResponse from(VideoProgress progress) {
            return new ProgressResponse(progress.getVideoFileId(), progress.getPositionSeconds(),
                    progress.getDurationSeconds(), progress.isCompleted());
        }
    }
}
