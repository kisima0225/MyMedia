package com.mymedia.video;

import com.mymedia.shared.MaterializedPath;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 维护视频库的目录树浏览视图。
 *
 * <p>把文件的相对路径按分隔符拆开，逐层查找或创建 {@link VideoFolder}，
 * 最后把条目挂到最深一层目录上。
 *
 * <p>物化路径包含节点自身的 id，因此必须先 INSERT 拿到 id、再补全路径。
 */
@Service
class VideoFolderIndexer {

    private final VideoFolderRepository folderRepository;

    VideoFolderIndexer(VideoFolderRepository folderRepository) {
        this.folderRepository = folderRepository;
    }

    @Transactional
    void attachItemToFolder(Long libraryId, String relativePath, VideoItem item) {
        int lastSlash = relativePath.lastIndexOf('/');
        if (lastSlash < 0) {
            // 文件直接躺在库根目录，不属于任何子目录
            return;
        }

        String[] segments = relativePath.substring(0, lastSlash).split("/");
        Long parentId = null;
        String parentPath = MaterializedPath.rootPath();
        List<VideoFolder> hierarchy = new ArrayList<>();

        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            VideoFolder folder = findOrCreate(libraryId, parentId, parentPath, segment);
            hierarchy.add(folder);
            parentId = folder.getId();
            // findOrCreate 返回的目录已经完成最终化，后续节点必须使用这个路径。
            parentPath = folder.getMaterializedPath();
        }

        if (parentId == null || item.getFolderId() != null) {
            return;
        }

        item.assignFolder(parentId);
        hierarchy.getLast().incrementDirectItemCount();
        hierarchy.forEach(VideoFolder::incrementTotalItemCount);
    }

    private VideoFolder findOrCreate(Long libraryId, Long parentId, String parentPath, String name) {
        var existing = parentId == null
                ? folderRepository.findByLibraryIdAndParentIdIsNullAndName(libraryId, name)
                : folderRepository.findByLibraryIdAndParentIdAndName(libraryId, parentId, name);

        if (existing.isPresent()) {
            return existing.get();
        }

        VideoFolder created = folderRepository.saveAndFlush(
                new VideoFolder(libraryId, parentId, parentPath, name));
        created.finalizePath(parentPath);
        return folderRepository.saveAndFlush(created);
    }
}
