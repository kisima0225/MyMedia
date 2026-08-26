package com.mymedia.image.web;

import com.mymedia.image.ImageProgress;
import com.mymedia.image.ImageProgressService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
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
@RequestMapping("/api/image")
class ImageProgressController {

    private final ImageProgressService progressService;
    private final UserQueryService userQueryService;

    ImageProgressController(ImageProgressService progressService,
                            UserQueryService userQueryService) {
        this.progressService = progressService;
        this.userQueryService = userQueryService;
    }

    @PutMapping("/progress/{nodeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void record(@AuthenticationPrincipal UserDetails principal,
                @PathVariable Long nodeId,
                @Valid @RequestBody ProgressRequest request) {
        progressService.record(currentUserId(principal), nodeId, request.pageIndex());
    }

    @GetMapping("/continue-reading")
    List<ImageProgressService.ContinueReadingEntry> continueReading(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(defaultValue = "20") int limit) {
        return progressService.continueReading(currentUserId(principal), limit);
    }

    private Long currentUserId(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
    }

    record ProgressRequest(@Min(0) int pageIndex) {
    }

    record ProgressResponse(Long nodeId, int pageIndex) {

        static ProgressResponse from(ImageProgress progress) {
            return new ProgressResponse(progress.getImageNodeId(), progress.getPageIndex());
        }
    }
}
