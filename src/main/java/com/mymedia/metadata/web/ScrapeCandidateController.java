package com.mymedia.metadata.web;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.metadata.ScrapeCandidateRecord;
import com.mymedia.metadata.ScrapeCandidateService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoCatalogService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 待确认队列：列出、确认、忽略。 */
@RestController
@RequestMapping("/api/scrape")
class ScrapeCandidateController {

    private final ScrapeCandidateService candidateService;
    private final VideoCatalogService videoCatalog;
    private final ImageCatalogService imageCatalog;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    ScrapeCandidateController(ScrapeCandidateService candidateService,
                              VideoCatalogService videoCatalog,
                              ImageCatalogService imageCatalog,
                              LibraryAccessService accessService,
                              UserQueryService userQueryService) {
        this.candidateService = candidateService;
        this.videoCatalog = videoCatalog;
        this.imageCatalog = imageCatalog;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @GetMapping("/candidates")
    List<MetadataDto.CandidateResponse> list(@AuthenticationPrincipal UserDetails principal,
                                             @RequestParam LibraryDomain domain,
                                             @RequestParam Long targetId) {
        requireAccess(principal, domain, targetId);
        return candidateService.candidatesFor(domain, targetId).stream()
                .map(MetadataDto.CandidateResponse::from)
                .toList();
    }

    @PostMapping("/candidates/{id}/confirm")
    MetadataDto.Response confirm(@AuthenticationPrincipal UserDetails principal,
                                 @PathVariable Long id) {
        ScrapeCandidateRecord candidate = candidateService.candidateById(id);
        requireAccess(principal, candidate.domain(), candidate.targetId());
        return MetadataDto.Response.from(candidateService.confirm(id));
    }

    @PostMapping("/ignore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void ignore(@AuthenticationPrincipal UserDetails principal,
                @RequestParam LibraryDomain domain,
                @RequestParam Long targetId) {
        requireAccess(principal, domain, targetId);
        candidateService.ignore(domain, targetId);
    }

    private void requireAccess(UserDetails principal, LibraryDomain domain, Long targetId) {
        Long libraryId = domain == LibraryDomain.VIDEO
                ? videoCatalog.getItem(targetId).getLibraryId()
                : imageCatalog.getNode(targetId).getLibraryId();
        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
        if (!accessService.canAccess(userId, libraryId)) {
            // 404 而非 403：不泄露资源存在性。
            throw new NotFoundException("找不到该条目 id=" + targetId);
        }
    }
}
