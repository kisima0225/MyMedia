package com.mymedia.image;

import com.mymedia.shared.MaterializedPath;
import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 图片库的树浏览。
 *
 * <p>与视频域不同，这里的树是<b>主浏览方式</b>而非次要视图——
 * 图片内容的组织方式高度个人化，系统不替用户决定层级。
 */
@Service
public class ImageBrowseService {

    private final ImageNodeRepository nodeRepository;

    ImageBrowseService(ImageNodeRepository nodeRepository) {
        this.nodeRepository = nodeRepository;
    }

    @Transactional(readOnly = true)
    public List<ImageNode> childNodes(Long libraryId, Long nodeId) {
        return nodeId == null
                ? nodeRepository.findByLibraryIdAndParentIdIsNullOrderBySortKey(libraryId)
                : nodeRepository.findByParentIdOrderBySortKey(nodeId);
    }

    @Transactional(readOnly = true)
    public ImageNode getNode(Long nodeId) {
        return nodeRepository.findById(nodeId)
                .orElseThrow(() -> new NotFoundException("找不到图片节点 id=" + nodeId));
    }

    /**
     * 面包屑导航。
     *
     * <p>直接从物化路径解析出全部祖先 id，<b>一次查询搞定，不需要递归</b>——
     * 这正是存物化路径的主要收益。深度 10 的树也只有一次 {@code IN} 查询。
     */
    @Transactional(readOnly = true)
    public List<ImageNode> breadcrumb(Long nodeId) {
        ImageNode node = getNode(nodeId);
        List<Long> ancestorIds = MaterializedPath.ancestorIds(node.getMaterializedPath());
        List<ImageNode> nodes = nodeRepository.findAllById(ancestorIds);
        // findAllById 不保证顺序，按物化路径中的顺序重排
        return ancestorIds.stream()
                .map(id -> nodes.stream()
                        .filter(candidate -> candidate.getId().equals(id))
                        .findFirst().orElseThrow())
                .toList();
    }
}
