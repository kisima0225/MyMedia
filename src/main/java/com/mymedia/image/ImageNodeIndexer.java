package com.mymedia.image;

import com.mymedia.image.event.ImageNodeCreated;
import com.mymedia.library.LibraryService;
import com.mymedia.shared.MaterializedPath;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把文件的相对路径变成一条节点链。
 *
 * <p>物化路径包含节点自身的 id，因此必须<b>先 INSERT 拿到 id、再补全路径</b>，
 * 这就是 {@link ImageNode#finalizePaths} 存在的原因。
 */
@Service
class ImageNodeIndexer {

    private final ImageNodeRepository nodeRepository;
    private final LibraryService libraryService;
    private final ApplicationEventPublisher events;

    ImageNodeIndexer(ImageNodeRepository nodeRepository,
                     LibraryService libraryService,
                     ApplicationEventPublisher events) {
        this.nodeRepository = nodeRepository;
        this.libraryService = libraryService;
        this.events = events;
    }

    /**
     * 返回该文件所在目录的节点，逐层 find-or-create。
     *
     * <p>文件直接躺在库根目录时没有目录可挂，此时建一个<b>以媒体库名命名的顶层节点</b>
     * 收容它们——库根下的散图同样必须可读，不能悄悄丢掉。
     */
    @Transactional
    ImageNode directoryNodeFor(Long libraryId, String relativePath) {
        int lastSlash = relativePath.lastIndexOf('/');
        if (lastSlash < 0) {
            String libraryName = libraryService.getById(libraryId).getName();
            return findOrCreateChild(libraryId, null, libraryName);
        }
        return walk(libraryId, relativePath.substring(0, lastSlash));
    }

    /**
     * 返回压缩包自身的叶子节点。
     *
     * <p>节点名去掉扩展名：{@code vol01.cbz} 显示为 {@code vol01}。
     */
    @Transactional
    ImageNode archiveNodeFor(Long libraryId, String relativePath, Long archiveScannedFileId) {
        var existing = nodeRepository.findByArchiveScannedFileId(archiveScannedFileId);
        if (existing.isPresent()) {
            return existing.get();
        }

        int lastSlash = relativePath.lastIndexOf('/');
        ImageNode parent = lastSlash < 0 ? null : walk(libraryId, relativePath.substring(0, lastSlash));
        String fileName = relativePath.substring(lastSlash + 1);
        String nodeName = stripExtension(fileName);

        Long parentId = parent == null ? null : parent.getId();
        var sameName = parentId == null
                ? nodeRepository.findByLibraryIdAndParentIdIsNullAndName(libraryId, nodeName)
                : nodeRepository.findByLibraryIdAndParentIdAndName(libraryId, parentId, nodeName);
        if (sameName.isPresent()) {
            // 同目录下已有同名节点（例如同时存在 vol01/ 目录与 vol01.cbz），
            // 沿用既有节点，避免撞上兄弟唯一约束。
            return sameName.get();
        }

        String parentPath = parent == null ? MaterializedPath.rootPath() : parent.getMaterializedPath();
        String parentSortPath = parent == null ? MaterializedPath.rootPath() : parent.getSortPath();

        ImageNode created = nodeRepository.saveAndFlush(ImageNode.archive(
                libraryId, parentId, parentPath, parentSortPath, nodeName, archiveScannedFileId));
        created.finalizePaths(parentPath, parentSortPath);
        ImageNode saved = nodeRepository.saveAndFlush(created);
        events.publishEvent(new ImageNodeCreated(saved.getId(), libraryId, nodeName));
        return saved;
    }

    @Transactional
    ImageNode findOrCreateChild(Long libraryId, ImageNode parent, String name) {
        Long parentId = parent == null ? null : parent.getId();
        var existing = parentId == null
                ? nodeRepository.findByLibraryIdAndParentIdIsNullAndName(libraryId, name)
                : nodeRepository.findByLibraryIdAndParentIdAndName(libraryId, parentId, name);
        if (existing.isPresent()) {
            return existing.get();
        }

        String parentPath = parent == null ? MaterializedPath.rootPath() : parent.getMaterializedPath();
        String parentSortPath = parent == null ? MaterializedPath.rootPath() : parent.getSortPath();

        ImageNode created = nodeRepository.saveAndFlush(
                ImageNode.directory(libraryId, parentId, parentPath, parentSortPath, name));
        // 路径含自身 id，只能在拿到 id 之后补全
        created.finalizePaths(parentPath, parentSortPath);
        ImageNode saved = nodeRepository.saveAndFlush(created);
        events.publishEvent(new ImageNodeCreated(saved.getId(), libraryId, name));
        return saved;
    }

    private ImageNode walk(Long libraryId, String directoryPath) {
        ImageNode current = null;
        for (String segment : directoryPath.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            current = findOrCreateChild(libraryId, current, segment);
        }
        return current;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }
}