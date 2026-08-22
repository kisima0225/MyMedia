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
        return directoryPathNode(libraryId, lastSlash < 0 ? "" : relativePath.substring(0, lastSlash));
    }

    /**
     * 返回压缩包自身的叶子节点。
     *
     * <p>节点名去掉扩展名：{@code vol01.cbz} 显示为 {@code vol01}。
     * 若同目录下已存在同名 DIRECTORY 节点（如 {@code vol01/} 目录与 {@code vol01.cbz}
     * 并存——解压后未删除原压缩包），返回 {@code null}：目录已含解压后的页，压缩包是冗余副本。
     */
    @Transactional
    ImageNode archiveNodeFor(Long libraryId, String relativePath, Long archiveScannedFileId) {
        var existing = nodeRepository.findByArchiveScannedFileId(archiveScannedFileId);
        if (existing.isPresent()) {
            return existing.get();
        }

        int lastSlash = relativePath.lastIndexOf('/');
        ImageNode parent = lastSlash < 0 ? null : directoryPathNode(libraryId, relativePath.substring(0, lastSlash));
        String fileName = relativePath.substring(lastSlash + 1);
        String nodeName = stripExtension(fileName);

        Long parentId = parent == null ? null : parent.getId();
        var sameName = parentId == null
                ? nodeRepository.findByLibraryIdAndParentIdIsNullAndName(libraryId, nodeName)
                : nodeRepository.findByLibraryIdAndParentIdAndName(libraryId, parentId, nodeName);
        if (sameName.isPresent()) {
            if (sameName.get().getSourceKind() == ImageSourceKind.ARCHIVE) {
                // 同名 ARCHIVE 节点：沿用，避免撞上兄弟唯一约束
                return sameName.get();
            }
            // 同名 DIRECTORY 节点（如 vol01/ 目录与 vol01.cbz 并存）：目录已含解压后的页，
            // 压缩包是冗余副本。返回 null，调用方跳过建索引并记 WARN——
            // 若照常排索引任务，handler 按 scannedFileId 找不到节点会永久 FAILED。
            return null;
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

    /** 按目录路径查找或创建节点链。{@code directoryPath} 为空串时返回库根收容节点。 */
    @Transactional
    ImageNode directoryPathNode(Long libraryId, String directoryPath) {
        if (directoryPath.isEmpty()) {
            return findOrCreateChild(libraryId, null, libraryService.getById(libraryId).getName());
        }
        ImageNode current = null;
        for (String segment : directoryPath.split("/")) {
            if (!segment.isEmpty()) {
                current = findOrCreateChild(libraryId, current, segment);
            }
        }
        return current;
    }

    /**
     * 按目录路径只查找、不创建。任何一层不存在就返回 {@code null}。
     *
     * <p>改名与移动的处理需要区分"这个目录还在树里"和"它已经跟着祖先一起搬走了"，
     * 用 find-or-create 会把后者错认成前者并造出一个空壳。
     */
    @Transactional(readOnly = true)
    ImageNode resolveDirectory(Long libraryId, String directoryPath) {
        if (directoryPath.isEmpty()) {
            return nodeRepository.findByLibraryIdAndParentIdIsNullAndName(
                    libraryId, libraryService.getById(libraryId).getName()).orElse(null);
        }
        ImageNode current = null;
        for (String segment : directoryPath.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            var found = current == null
                    ? nodeRepository.findByLibraryIdAndParentIdIsNullAndName(libraryId, segment)
                    : nodeRepository.findByLibraryIdAndParentIdAndName(libraryId, current.getId(), segment);
            if (found.isEmpty()) {
                return null;
            }
            current = found.get();
        }
        return current;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }
}
