package com.mymedia.preview.web;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.preview.DerivedAsset;
import com.mymedia.preview.DerivedAssetKind;
import com.mymedia.preview.DerivedAssetService;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoFile;
import com.mymedia.video.VideoItem;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 按视频文件查它的派生资源 id。
 *
 * <p><b>前端没有别的办法拿到雪碧图</b>：{@code /api/assets/{id}} 按 id 取内容，
 * 而 id 在 {@code derived_asset} 表里，前端手上只有 {@code videoFileId}。
 * 计划 05 花了一个任务生成 100 帧雪碧图与 WebVTT，没有这个端点它们就是死数据。
 *
 * <p><b>为什么在 {@code preview} 而不是 {@code video}</b>：{@code video} 的
 * {@code allowedDependencies} 里没有 {@code preview}，也绝不能加——领域模块依赖
 * 派生资源模块是反方向的。{@code preview} 本来就允许依赖 {@code video}，
 * 由它做 {@code videoFileId → scannedFileId → derived_asset} 的串联是唯一
 * 不破坏依赖方向的位置（总览 §4.2）。
 */
@RestController
@RequestMapping("/api/preview")
class VideoPreviewController {

    private final VideoCatalogService catalogService;
    private final DerivedAssetService assetService;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    VideoPreviewController(VideoCatalogService catalogService,
                           DerivedAssetService assetService,
                           LibraryAccessService accessService,
                           UserQueryService userQueryService) {
        this.catalogService = catalogService;
        this.assetService = assetService;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @GetMapping("/video/{videoFileId}")
    VideoPreviewView preview(@AuthenticationPrincipal UserDetails principal,
                             @PathVariable Long videoFileId) {

        VideoFile file = catalogService.getFile(videoFileId);
        VideoItem item = catalogService.getItem(file.getItemId());

        Long userId = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户")).getId();
        if (!accessService.canAccess(userId, item.getLibraryId())) {
            // 404 而非 403：不泄露资源存在性
            throw new NotFoundException("找不到视频文件 id=" + videoFileId);
        }

        Long source = file.getScannedFileId();
        return new VideoPreviewView(
                videoFileId,
                assetId(DerivedAssetKind.COVER, source),
                assetId(DerivedAssetKind.THUMBNAIL, source),
                assetId(DerivedAssetKind.SPRITE_SHEET, source),
                assetId(DerivedAssetKind.SPRITE_VTT, source));
    }

    /** 尚未生成的资源返回 null——它是「还没轮到」，不是错误。 */
    private Long assetId(DerivedAssetKind kind, Long sourceScannedFileId) {
        return assetService.find(kind, sourceScannedFileId)
                .map(DerivedAsset::getId)
                .orElse(null);
    }

    record VideoPreviewView(Long videoFileId, Long coverAssetId, Long thumbnailAssetId,
                            Long spriteAssetId, Long spriteVttAssetId) {
    }
}
