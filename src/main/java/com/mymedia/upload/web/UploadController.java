package com.mymedia.upload.web;

import com.mymedia.shared.NotFoundException;
import com.mymedia.upload.UploadSessionService;
import com.mymedia.user.UserQueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/upload")
class UploadController {

    private final UploadSessionService sessionService;
    private final UserQueryService userQueryService;

    UploadController(UploadSessionService sessionService, UserQueryService userQueryService) {
        this.sessionService = sessionService;
        this.userQueryService = userQueryService;
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    UploadDto.Response create(@AuthenticationPrincipal UserDetails principal,
                              @Valid @RequestBody UploadDto.CreateRequest request) {
        return UploadDto.Response.from(
                sessionService.create(currentUserId(principal), request.filename(),
                        request.totalSize(), request.contentHash(), request.targetLibraryId()),
                List.of());
    }

    /** 断点续传的入口：客户端问「我传到哪儿了」。Task 11 会把已收分片填进来。 */
    @GetMapping("/sessions/{id}")
    UploadDto.Response get(@AuthenticationPrincipal UserDetails principal,
                           @PathVariable Long id) {
        return UploadDto.Response.from(
                sessionService.get(currentUserId(principal), id), List.of());
    }

    private Long currentUserId(UserDetails principal) {
        return userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
    }
}
