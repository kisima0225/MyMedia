package com.mymedia.image;

import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@code image} 模块对外暴露的节点与页查询能力。
 */
@Service
public class ImageCatalogService {

    private final ImageNodeRepository nodeRepository;
    private final ImageFileRepository fileRepository;

    ImageCatalogService(ImageNodeRepository nodeRepository, ImageFileRepository fileRepository) {
        this.nodeRepository = nodeRepository;
        this.fileRepository = fileRepository;
    }

    @Transactional(readOnly = true)
    public ImageNode getNode(Long nodeId) {
        return nodeRepository.findById(nodeId)
                .orElseThrow(() -> new NotFoundException("找不到图片节点 id=" + nodeId));
    }

    @Transactional(readOnly = true)
    public List<ImageNode> findRoots(Long libraryId) {
        return nodeRepository.findByLibraryIdAndParentIdIsNullOrderBySortKey(libraryId);
    }

    /**
     * 节点的页。
     *
     * <p>{@code FORCE_BOOK} 下返回<b>整棵子树</b>的页，按（顺序路径, 页序）展开，
     * 也就是「章节顺序 + 页顺序」。这正是 {@code sort_path} 列存在的理由：
     * 结构路径由 id 组成，它的顺序是节点创建顺序，拿它排序会得到扫描时的偶然次序。
     */
    @Transactional(readOnly = true)
    public List<ImageFile> pagesOf(Long nodeId) {
        ImageNode node = getNode(nodeId);
        if (node.getReadingMode() == ImageReadingMode.FORCE_BOOK) {
            return fileRepository.findSubtreePages(
                    node.getLibraryId(), node.getMaterializedPath() + "%");
        }
        return fileRepository.findByNodeIdOrderByPageIndex(nodeId);
    }

    @Transactional(readOnly = true)
    public ImageFile getFile(Long fileId) {
        return fileRepository.findById(fileId)
                .orElseThrow(() -> new NotFoundException("找不到图片文件 id=" + fileId));
    }

    /** 用户推翻自动判定。 */
    @Transactional
    public ImageNode setReadingMode(Long nodeId, ImageReadingMode mode) {
        ImageNode node = getNode(nodeId);
        node.overrideReadingMode(mode);
        return nodeRepository.save(node);
    }
}