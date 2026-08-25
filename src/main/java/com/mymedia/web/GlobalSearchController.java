package com.mymedia.web;

import com.mymedia.image.ImageSearchService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.shared.SearchQuery;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoSearchService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局搜索：一次输入，两个域各查各的，分区返回。
 *
 * <p>两次查询是顺序发出的，没有并行化——它们各自都是一条走索引的 SQL，
 * 加起来通常在几十毫秒内。为省这点时间引入线程池、再引入线程池的配置与
 * 关闭逻辑，是典型的用复杂度换不需要的性能。
 */
@RestController
class GlobalSearchController {

    private static final int MAX_LIMIT = 100;

    private final VideoSearchService videoSearch;
    private final ImageSearchService imageSearch;
    private final UserQueryService userQueryService;

    GlobalSearchController(VideoSearchService videoSearch,
                           ImageSearchService imageSearch,
                           UserQueryService userQueryService) {
        this.videoSearch = videoSearch;
        this.imageSearch = imageSearch;
        this.userQueryService = userQueryService;
    }

    @GetMapping("/api/search")
    GlobalSearchDto.Response search(@AuthenticationPrincipal UserDetails principal,
                                    @RequestParam("q") String q,
                                    @RequestParam(value = "limit", defaultValue = "20") int limit) {
        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
        SearchQuery query = SearchQuery.of(q);
        int capped = Math.clamp(limit, 1, MAX_LIMIT);

        return new GlobalSearchDto.Response(
                query.normalized(),
                videoSearch.search(userId, query, capped),
                imageSearch.search(userId, query, capped));
    }
}
