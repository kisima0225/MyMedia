package com.mymedia.video;

import com.mymedia.shared.MaterializedPath;
import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频库的目录树浏览（次要视图）。
 *
 * <p>主浏览方式是语义化的（见 {@link VideoCatalogService}）；本服务让用户
 * 能按自己的目录组织方式导航。
 */
@Service
public class VideoBrowseService {

    private final VideoFolderRepository folderRepository;
    private final VideoItemRepository itemRepository;

    VideoBrowseService(VideoFolderRepository folderRepository,
                       VideoItemRepository itemRepository) {
        this.folderRepository = folderRepository;
        this.itemRepository = itemRepository;
    }

    @Transactional(readOnly = true)
    public List<VideoFolder> childFolders(Long libraryId, Long folderId) {
        if (folderId == null) {
            return folderRepository.findByLibraryIdAndParentIdIsNullOrderBySortKey(libraryId);
        }

        getFolder(libraryId, folderId);
        return folderRepository.findByLibraryIdAndParentIdOrderBySortKey(libraryId, folderId);
    }

    @Transactional(readOnly = true)
    public List<VideoItem> itemsIn(Long folderId) {
        VideoFolder folder = getFolder(folderId);
        return itemRepository.findByLibraryIdAndFolderIdOrderBySortTitle(
                folder.getLibraryId(), folderId);
    }

    @Transactional(readOnly = true)
    public List<VideoItem> itemsIn(Long libraryId, Long folderId) {
        getFolder(libraryId, folderId);
        return itemRepository.findByLibraryIdAndFolderIdOrderBySortTitle(libraryId, folderId);
    }

    @Transactional(readOnly = true)
    public VideoFolder getFolder(Long folderId) {
        return folderRepository.findById(folderId)
                .orElseThrow(() -> new NotFoundException("找不到目录 id=" + folderId));
    }

    @Transactional(readOnly = true)
    public VideoFolder getFolder(Long libraryId, Long folderId) {
        return folderRepository.findByLibraryIdAndId(libraryId, folderId)
                .orElseThrow(() -> new NotFoundException("找不到目录 id=" + folderId));
    }

    /**
     * 面包屑导航。
     *
     * <p>直接从物化路径解析出全部祖先 id，一次查询搞定，不需要递归。
     */
    @Transactional(readOnly = true)
    public List<VideoFolder> breadcrumb(Long folderId) {
        VideoFolder folder = getFolder(folderId);
        return breadcrumb(folder, folder.getLibraryId());
    }

    /**
     * 在指定媒体库内解析面包屑，并拒绝属于其他媒体库的目录。
     */
    @Transactional(readOnly = true)
    public List<VideoFolder> breadcrumb(Long libraryId, Long folderId) {
        return breadcrumb(getFolder(libraryId, folderId), libraryId);
    }

    private List<VideoFolder> breadcrumb(VideoFolder folder, Long libraryId) {
        List<Long> ancestorIds = MaterializedPath.ancestorIds(folder.getMaterializedPath());
        List<VideoFolder> folders = folderRepository.findAllByLibraryIdAndIdIn(libraryId, ancestorIds);
        Map<Long, VideoFolder> foldersById = new HashMap<>();
        folders.forEach(candidate -> foldersById.put(candidate.getId(), candidate));

        if (foldersById.size() != ancestorIds.size()) {
            throw new NotFoundException("找不到目录祖先 id=" + folder.getId());
        }
        return ancestorIds.stream().map(foldersById::get).toList();
    }
}
