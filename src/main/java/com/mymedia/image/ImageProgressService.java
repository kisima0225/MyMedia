package com.mymedia.image;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.shared.NotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

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

    @Transactional(readOnly = true)
    public List<ImageProgress> continueReading(Long userId, int limit) {
        return repository.findContinueReading(userId, PageRequest.of(0, limit));
    }
}
