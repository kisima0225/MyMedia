package com.mymedia.image;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.shared.NotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ImageProgressService {

    private final ImageProgressRepository repository;
    private final ImageCatalogService catalogService;
    private final LibraryAccessService accessService;

    ImageProgressService(ImageProgressRepository repository,
                         ImageCatalogService catalogService,
                         LibraryAccessService accessService) {
        this.repository = repository;
        this.catalogService = catalogService;
        this.accessService = accessService;
    }

    @Transactional
    public void record(Long userId, Long nodeId, int pageIndex) {
        ImageNode node = catalogService.getNode(nodeId);      // 不存在则抛 NotFoundException
        if (!accessService.canAccess(userId, node.getLibraryId())) {
            // 404 而非 403：不泄露资源存在性
            throw new NotFoundException("找不到图片节点 id=" + nodeId);
        }

        ImageProgress progress = repository.findByUserIdAndImageNodeId(userId, nodeId)
                .orElseGet(() -> new ImageProgress(userId, nodeId));
        progress.update(pageIndex);
        repository.save(progress);
    }

    @Transactional(readOnly = true)
    public Optional<ImageProgress> find(Long userId, Long nodeId) {
        return repository.findByUserIdAndImageNodeId(userId, nodeId);
    }

    /** 见 {@code VideoProgressService.continueWatching} 的注释，理由完全相同。 */
    @Transactional(readOnly = true)
    public List<ContinueReadingEntry> continueReading(Long userId, int limit) {
        List<ImageProgress> progresses =
                repository.findContinueReading(userId, PageRequest.of(0, limit));
        if (progresses.isEmpty()) {
            return List.of();
        }

        Map<Long, ImageNode> nodesById = catalogService
                .findByIds(progresses.stream().map(ImageProgress::getImageNodeId).toList())
                .stream()
                .filter(node -> accessService.canAccess(userId, node.getLibraryId()))
                .collect(Collectors.toMap(ImageNode::getId, Function.identity()));

        return progresses.stream()
                .map(progress -> {
                    ImageNode node = nodesById.get(progress.getImageNodeId());
                    if (node == null) {
                        return null;
                    }
                    return new ContinueReadingEntry(
                            node.getId(), node.getDisplayName(), node.getCoverAssetId(),
                            progress.getPageIndex(), node.getTotalPageCount());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /** {@code nodeTitle} 用 {@code getDisplayName()}——刮削到标题就用标题，没有就用目录原名。 */
    public record ContinueReadingEntry(
            Long nodeId, String nodeTitle, Long coverAssetId, int pageIndex, int totalPageCount) {
    }
}
