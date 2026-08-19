package com.mymedia.video;

import org.springframework.stereotype.Service;

/**
 * 视频目录派生索引的暂时适配点；Task 6 会替换为真正的索引器。
 */
@Service
class VideoFolderIndexer {

    void attachItemToFolder(Long libraryId, String relativePath, VideoItem item) {
        // Task 6 实现
    }
}
