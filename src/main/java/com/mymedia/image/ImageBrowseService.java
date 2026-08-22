package com.mymedia.image;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 临时占位，Task 7 会替换为完整实现
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
}