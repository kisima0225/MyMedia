package com.mymedia.video;

import com.mymedia.library.LibraryDomain;
import com.mymedia.scan.event.ScannedFileChanged;
import com.mymedia.scan.event.ScannedFileDiscovered;
import com.mymedia.scan.event.ScannedFileVanished;
import com.mymedia.scan.spi.LibraryContentBuilder;
import com.mymedia.scan.spi.MediaKind;
import com.mymedia.video.event.VideoItemCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把扫描发现的视频文件构建成语义结构。
 *
 * <p>归并规则：
 * <ol>
 *   <li>解析出季或集号按标题归并到同一个条目，条目提升为 {@code GROUPED}</li>
 *   <li>解析出年份、无季集时创建 {@code MOVIE}、{@code FLAT} 条目</li>
 *   <li>什么都没解析出来时创建 {@code SINGLE_VIDEO}、{@code FLAT} 条目，标题即文件名</li>
 * </ol>
 *
 * <p>同一个物理文件通过 {@code scannedFileId} 做幂等映射。
 */
@Component
class VideoContentBuilder implements LibraryContentBuilder {

    private static final Logger log = LoggerFactory.getLogger(VideoContentBuilder.class);

    private final VideoItemRepository itemRepository;
    private final VideoGroupRepository groupRepository;
    private final VideoFileRepository fileRepository;
    private final VideoFolderIndexer folderIndexer;
    private final ApplicationEventPublisher events;

    VideoContentBuilder(VideoItemRepository itemRepository,
                        VideoGroupRepository groupRepository,
                        VideoFileRepository fileRepository,
                        VideoFolderIndexer folderIndexer,
                        ApplicationEventPublisher events) {
        this.itemRepository = itemRepository;
        this.groupRepository = groupRepository;
        this.fileRepository = fileRepository;
        this.folderIndexer = folderIndexer;
        this.events = events;
    }

    @Override
    public boolean supports(LibraryDomain domain) {
        return domain == LibraryDomain.VIDEO;
    }

    @Override
    @Transactional
    public void onFileDiscovered(ScannedFileDiscovered event) {
        if (event.kind() != MediaKind.VIDEO) {
            return;
        }

        // 幂等保护：同一个物理文件只建一次语义记录。
        if (fileRepository.findByScannedFileId(event.scannedFileId()).isPresent()) {
            return;
        }

        ParsedVideoName parsed = VideoFilenameParser.parse(event.relativePath());
        VideoItem item = findOrCreateItem(event.libraryId(), parsed, event.relativePath());

        VideoFile file = new VideoFile(
                event.scannedFileId(), item.getId(), VideoFileRole.PRIMARY, event.relativePath());

        if (parsed.season() != null || parsed.episode() != null) {
            if (item.getStructure() != VideoStructure.GROUPED) {
                item.promoteToGrouped();
            }
            int seasonIndex = parsed.season() == null ? 1 : parsed.season();
            VideoGroup group = findOrCreateGroup(item.getId(), seasonIndex);
            file.assignGroup(group.getId(), parsed.episode());
        }

        fileRepository.saveAndFlush(file);
        folderIndexer.attachItemToFolder(event.libraryId(), event.relativePath(), item);
    }

    @Override
    @Transactional
    public void onFileChanged(ScannedFileChanged event) {
        // 视频文件的物理身份由 scanned_file_id 维持；本任务不处理探测元数据。
        log.debug("视频文件元数据变化: {}", event.relativePath());
    }

    @Override
    @Transactional
    public void onFileVanished(ScannedFileVanished event) {
        // 语义层不做任何删除：物理层已标记 MISSING，条目仍在。
        log.debug("视频文件不可用: {}", event.relativePath());
    }

    private VideoItem findOrCreateItem(Long libraryId, ParsedVideoName parsed, String relativePath) {
        return itemRepository.findByLibraryIdAndTitle(libraryId, parsed.title())
                .orElseGet(() -> {
                    VideoItemType type = inferType(parsed);
                    VideoStructure structure = hasSeasonOrEpisode(parsed)
                            ? VideoStructure.GROUPED
                            : VideoStructure.FLAT;
                    VideoItem created = itemRepository.saveAndFlush(
                            new VideoItem(libraryId, type, structure, parsed.title()));
                    log.info("新建视频条目 id={} title={} type={} from={}",
                            created.getId(), parsed.title(), type, relativePath);
                    events.publishEvent(new VideoItemCreated(
                            created.getId(), libraryId, parsed.title()));
                    return created;
                });
    }

    private VideoGroup findOrCreateGroup(Long itemId, int seasonIndex) {
        return groupRepository.findByItemIdAndGroupIndex(itemId, seasonIndex)
                .orElseGet(() -> groupRepository.saveAndFlush(
                        new VideoGroup(itemId, seasonIndex, "第 " + seasonIndex + " 季")));
    }

    private static VideoItemType inferType(ParsedVideoName parsed) {
        if (hasSeasonOrEpisode(parsed)) {
            return VideoItemType.SERIES;
        }
        if (parsed.year() != null) {
            return VideoItemType.MOVIE;
        }
        return VideoItemType.SINGLE_VIDEO;
    }

    private static boolean hasSeasonOrEpisode(ParsedVideoName parsed) {
        return parsed.season() != null || parsed.episode() != null;
    }
}
