package com.mymedia.image.web;

import com.mymedia.image.ImageSearchHit;
import com.mymedia.image.ImageSearchService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.shared.SearchQuery;
import com.mymedia.user.UserQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/image/search")
class ImageSearchController {

    private static final int MAX_LIMIT = 100;

    private final ImageSearchService searchService;
    private final UserQueryService userQueryService;

    ImageSearchController(ImageSearchService searchService, UserQueryService userQueryService) {
        this.searchService = searchService;
        this.userQueryService = userQueryService;
    }

    @GetMapping
    List<ImageSearchHit> search(@AuthenticationPrincipal UserDetails principal,
                                @RequestParam("q") String q,
                                @RequestParam(value = "limit", defaultValue = "20") int limit) {
        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
        // SearchQuery.of 对空白输入抛 IllegalArgumentException，
        // GlobalExceptionHandler 会把它翻成 400
        return searchService.search(userId, SearchQuery.of(q), Math.clamp(limit, 1, MAX_LIMIT));
    }
}
