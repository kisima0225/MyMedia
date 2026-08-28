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

    /**
     * 全局待确认队列：{@code /candidates?domain=&targetId=} 只能查单个目标，
     * 管理界面需要"当前一共有哪些待确认目标"，所以按目标去重列出，逐个校验访问权
     * （与 {@link #requireAccess} 同一套 404-而非-403 规矩，只是这里用过滤而不是抛错——
     * 无权访问的目标直接从列表里消失，不需要让整个队列请求失败）。
     */
    @GetMapping("/queue")
    List<MetadataDto.QueueEntry> queue(@AuthenticationPrincipal UserDetails principal) {
        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
        return candidateService.pendingTargets().stream()
                .filter(t -> accessService.canAccess(userId, libraryIdOf(t)))
                .map(this::toQueueEntry)
                .toList();
    }

    private Long libraryIdOf(ScrapeCandidateService.PendingTarget target) {
        return target.domain() == LibraryDomain.VIDEO
                ? videoCatalog.getItem(target.targetId()).getLibraryId()
                : imageCatalog.getNode(target.targetId()).getLibraryId();
    }

    private MetadataDto.QueueEntry toQueueEntry(ScrapeCandidateService.PendingTarget target) {
        String title;
        Long coverAssetId;
        if (target.domain() == LibraryDomain.VIDEO) {
            var item = videoCatalog.getItem(target.targetId());
            title = item.getTitle();
            coverAssetId = item.getCoverAssetId();
        } else {
            var node = imageCatalog.getNode(target.targetId());
            title = node.getDisplayName();
            coverAssetId = node.getCoverAssetId();
        }
        List<MetadataDto.CandidateResponse> candidates = candidateService
                .candidatesFor(target.domain(), target.targetId())
                .stream().map(MetadataDto.CandidateResponse::from).toList();
        return new MetadataDto.QueueEntry(target.domain(), target.targetId(), title, coverAssetId, candidates);
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
