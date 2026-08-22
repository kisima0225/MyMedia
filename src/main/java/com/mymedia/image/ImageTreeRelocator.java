package com.mymedia.image;

import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.scan.event.ScannedFileRelocated;
import com.mymedia.shared.MaterializedPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把物理层的「文件移动」翻译成语义层的「节点改名 / 子树移动」。
 *
 * <p>为什么需要它：物理层只知道一个个文件换了路径。若语义层照单全收地
 * 逐文件重挂，一个目录改名就会变成"建一个新节点、丢下一个旧节点"——
 * 旧节点上的阅读进度、收藏、阅读模式覆盖、刮削结果全部报废。
 *
 * <p>判定规则：把本轮的移动按（旧目录 → 新目录）分组。如果某组的文件数
 * <b>等于</b>旧目录节点下的全部文件数，说明整个目录搬走了，于是改名/移动节点本身；
 * 否则只是零散文件搬家，逐个重挂即可。
 *
 * <p>缓冲区是进程内的：应用在扫描中途崩溃会丢掉本轮判定，下次扫描退化成
 * 逐文件重挂。代价是一次目录级用户数据丢失，换来的是不引入额外的状态表——
 * 在单实例部署下这个取舍是划算的。
 */
@Service
class ImageTreeRelocator {

    private static final Logger log = LoggerFactory.getLogger(ImageTreeRelocator.class);

    private final Map<Long, List<Relocation>> pending = new ConcurrentHashMap<>();

    private final LibraryService libraryService;
    private final ImageNodeIndexer indexer;
    private final ImageNodeRepository nodeRepository;
    private final ImageFileRepository fileRepository;
    private final JdbcTemplate jdbc;

    ImageTreeRelocator(LibraryService libraryService,
                       ImageNodeIndexer indexer,
                       ImageNodeRepository nodeRepository,
                       ImageFileRepository fileRepository,
                       JdbcTemplate jdbc) {
        this.libraryService = libraryService;
        this.indexer = indexer;
        this.nodeRepository = nodeRepository;
        this.fileRepository = fileRepository;
        this.jdbc = jdbc;
    }

    @EventListener
    void on(ScannedFileRelocated event) {
        if (libraryService.getById(event.libraryId()).getDomain() != LibraryDomain.IMAGE) {
            return;
        }
        pending.computeIfAbsent(event.libraryId(),
                        key -> Collections.synchronizedList(new ArrayList<>()))
                .add(new Relocation(event.scannedFileId(), event.oldPath(), event.newPath()));
    }

    @Transactional
    void applyPending(Long libraryId) {
        List<Relocation> batch = pending.remove(libraryId);
        if (batch == null || batch.isEmpty()) {
            return;
        }

        Map<DirectoryMove, List<Relocation>> groups = new LinkedHashMap<>();
        for (Relocation relocation : batch) {
            DirectoryMove move = new DirectoryMove(
                    directoryOf(relocation.oldPath()), directoryOf(relocation.newPath()));
            groups.computeIfAbsent(move, key -> new ArrayList<>()).add(relocation);
        }

        // 浅的目录先处理：父目录一旦整体搬走，它下面各层的组会自动落到正确位置，
        // 后续的 resolveDirectory 会返回 null，走逐文件重挂的兜底路径（此时是空操作）。
        List<DirectoryMove> ordered = groups.keySet().stream()
                .sorted(Comparator.comparingInt(move -> segmentCount(move.oldDirectory())))
                .toList();

        for (DirectoryMove move : ordered) {
            List<Relocation> moved = groups.get(move);
            if (!tryMoveWholeDirectory(libraryId, move, batch)) {
                reattachIndividually(libraryId, moved);
            }
        }
    }

    private boolean tryMoveWholeDirectory(Long libraryId, DirectoryMove move,
                                          List<Relocation> batch) {
        if (move.oldDirectory().equals(move.newDirectory())) {
            return false;      // 同目录内改名，节点结构不变（压缩包除外，交给兜底路径）
        }
        ImageNode oldNode = indexer.resolveDirectory(libraryId, move.oldDirectory());
        if (oldNode == null || oldNode.getSourceKind() != ImageSourceKind.DIRECTORY) {
            return false;
        }

        // 从基候选开始逐层上溯：只要祖先的整棵子树都搬走了（子树内文件数 ==
        // 整批中旧路径前缀匹配的移动数），就继续上溯。判定通过才入链——
        // 判定失败的祖先绝不成为候选（部分搬迁时不能改名它）。
        // 整批而非本组：兄弟子树同时搬去同一新父时，共享祖先也要能整棵搬走。
        List<String> oldSegments = segmentsOf(move.oldDirectory());
        List<String> newSegments = segmentsOf(move.newDirectory());
        List<ImageNode> chain = new ArrayList<>();
        ImageNode current = oldNode;
        int level = oldSegments.size();
        while (current != null) {
            if (!wholeSubtreeMoved(libraryId, current, oldSegments, level, batch)) {
                break;
            }
            chain.add(current);
            level--;
            if (level == 0) {
                break;
            }
            current = nodeRepository.findById(current.getParentId()).orElse(null);
        }
        Collections.reverse(chain);
        if (chain.isEmpty()) {
            return false;
        }

        // chain 从最顶层祖先开始：第 i 个候选对应旧目录的前 (topLevel + i) 段。
        // 不提前返回：每个候选都要落实自己那段的改名（父子同时改名的多段场景），
        // 任一成功即视为整组已处理；全部失败回落逐文件重挂。
        int topLevel = oldSegments.size() - (chain.size() - 1);
        boolean any = false;
        for (int i = 0; i < chain.size(); i++) {
            // 新旧路径深度可以不同（目录被上移或下沉）。对齐必须<b>从底部的基候选</b>
            // 倒着数：基候选对应整个 newDirectory，往上每退一层就少取一段。
            // 直接用 topLevel + i 去切 newSegments 是错的 —— 上移时会越界。
            int newCount = newSegments.size() - oldSegments.size() + topLevel + i;
            if (newCount < 1) {
                // 这一层在新路径里根本没有对应段（子树被上移、中间层被抹平），
                // 该祖先不该跟着改名；留在原地，空了自会被重算回收。
                continue;
            }
            String newDir = joinSegments(newSegments, newCount);
            if (relocateNode(libraryId, chain.get(i),
                    parentDirectoryOf(newDir), lastSegmentOf(newDir))) {
                any = true;
            }
        }
        return any;
    }

    /**
     * 祖先目录的整棵子树（物化路径前缀）都随整批移动搬走了吗。
     *
     * <p>两侧都以「文件」计：子树内 DISTINCT scanned_file_id（散图与压缩包页统一），
     * 与整批 relocation 中旧路径落在该目录前缀下的数量对比。页数不能用来比——
     * 一个压缩包是一个文件、N 页，逐页计数会把只含压缩包的目录判成「没搬完」。
     */
    private boolean wholeSubtreeMoved(Long libraryId, ImageNode node, List<String> oldSegments,
                                      int level, List<Relocation> batch) {
        long filesInSubtree = jdbc.queryForObject("""
                SELECT count(DISTINCT f.scanned_file_id)
                FROM image_file f
                JOIN image_node n ON n.id = f.node_id
                WHERE n.library_id = ?
                  AND n.materialized_path LIKE ? || '%'
                """, Long.class, libraryId, node.getMaterializedPath());
        String prefix = joinSegments(oldSegments, level);
        long relocated = batch.stream()
                .filter(relocation -> relocation.oldPath().startsWith(prefix + "/"))
                .count();
        return filesInSubtree == relocated;
    }

    private void reattachIndividually(Long libraryId, List<Relocation> moved) {
        for (Relocation relocation : moved) {
            var archiveNode = nodeRepository.findByArchiveScannedFileId(relocation.scannedFileId());
            if (archiveNode.isPresent()) {
                // 压缩包换了位置或改了名：节点跟着走，页仍挂在它下面
                relocateNode(libraryId, archiveNode.get(),
                        directoryOf(relocation.newPath()),
                        stripExtension(fileNameOf(relocation.newPath())));
                continue;
            }
            ImageNode target = indexer.directoryNodeFor(libraryId, relocation.newPath());
            fileRepository.findByScannedFileId(relocation.scannedFileId())
                    .forEach(file -> file.reattachTo(target.getId()));
        }
    }

    /**
     * 把节点挪到新父之下并改成新名字，然后<b>一条 UPDATE 重写整棵子树的路径</b>。
     *
     * @return 是否完成；目标位置被真实内容占据时返回 {@code false}，由调用方兜底
     */
    private boolean relocateNode(Long libraryId, ImageNode node,
                                 String newParentDirectory, String newName) {
        ImageNode newParent = newParentDirectory.isEmpty()
                ? null
                : indexer.directoryPathNode(libraryId, newParentDirectory);
        Long newParentId = newParent == null ? null : newParent.getId();

        if (newParentId != null && newParentId.equals(node.getId())) {
            return false;      // 不能把节点挂到自己下面
        }

        if (!clearGhostAt(libraryId, newParentId, newName, node.getId())) {
            return false;
        }

        String oldPathPrefix = node.getMaterializedPath();
        String oldSortPrefix = node.getSortPath();
        String newParentPath = newParent == null
                ? MaterializedPath.rootPath() : newParent.getMaterializedPath();
        String newParentSortPath = newParent == null
                ? MaterializedPath.rootPath() : newParent.getSortPath();

        // 先改名（重算 sort_key），再移动（重算两条路径与深度）——
        // moveTo 用的是改名后的 sort_key，顺序不能反。
        node.rename(newName, newParentSortPath);
        node.moveTo(newParentId, newParentPath, newParentSortPath);
        nodeRepository.saveAndFlush(node);

        rewriteSubtree(libraryId, node.getId(),
                oldPathPrefix, node.getMaterializedPath(),
                oldSortPrefix, node.getSortPath());

        log.info("图片节点已跟随目录移动 id={} -> {}", node.getId(), node.getMaterializedPath());
        return true;
    }

    /**
     * 目标位置若已被一个空壳节点占着，先清掉。
     *
     * <p>空壳从哪来：扫描时先发布「发现新文件」再做改名配对，前者已经按新路径
     * 建好了节点，随后配对成功、新的 scanned_file 被删除，节点就空在那里了。
     * 不清掉它，改名过去会撞上兄弟唯一约束。
     *
     * @return 目标位置是否可用
     */
    private boolean clearGhostAt(Long libraryId, Long parentId, String name, Long movingNodeId) {
        var occupant = parentId == null
                ? nodeRepository.findByLibraryIdAndParentIdIsNullAndName(libraryId, name)
                : nodeRepository.findByLibraryIdAndParentIdAndName(libraryId, parentId, name);
        if (occupant.isEmpty() || occupant.get().getId().equals(movingNodeId)) {
            return true;
        }
        ImageNode ghost = occupant.get();
        boolean empty = fileRepository.countByNodeId(ghost.getId()) == 0
                && nodeRepository.findByParentIdOrderBySortKey(ghost.getId()).isEmpty();
        if (!empty) {
            log.debug("目标位置已有真实内容，放弃整目录移动: {}", name);
            return false;
        }
        nodeRepository.delete(ghost);
        nodeRepository.flush();
        return true;
    }

    /**
     * 子树路径重写：<b>一条前缀替换 UPDATE，不逐层递归</b>（spec §7.1 明确要求）。
     *
     * <p>深度直接在 SQL 里由新路径算出来：路径形如 {@code /1/17/93/}，
     * 深度就是斜杠数减一。
     *
     * <p>SET 子句右侧的 {@code materialized_path} 取的是<b>旧值</b>——
     * PostgreSQL 的 UPDATE 用行的原始值求值所有表达式，所以一条语句里
     * 既能读旧值又能写新值。
     */
    private void rewriteSubtree(Long libraryId, Long nodeId,
                                String oldPathPrefix, String newPathPrefix,
                                String oldSortPrefix, String newSortPrefix) {
        // 实体层的改动先落盘，否则下面这条原生 SQL 看不到它
        nodeRepository.flush();

        jdbc.update("""
                UPDATE image_node
                SET materialized_path = ? || substring(materialized_path FROM ?),
                    sort_path         = ? || substring(sort_path FROM ?),
                    depth = length(? || substring(materialized_path FROM ?))
                            - length(replace(? || substring(materialized_path FROM ?), '/', ''))
                            - 1
                WHERE library_id = ?
                  AND materialized_path LIKE ?
                  AND id <> ?
                """,
                newPathPrefix, oldPathPrefix.length() + 1,
                newSortPrefix, oldSortPrefix.length() + 1,
                newPathPrefix, oldPathPrefix.length() + 1,
                newPathPrefix, oldPathPrefix.length() + 1,
                libraryId,
                oldPathPrefix + "%",
                nodeId);
    }

    private static String directoryOf(String relativePath) {
        int lastSlash = relativePath.lastIndexOf('/');
        return lastSlash < 0 ? "" : relativePath.substring(0, lastSlash);
    }

    private static String fileNameOf(String relativePath) {
        int lastSlash = relativePath.lastIndexOf('/');
        return lastSlash < 0 ? relativePath : relativePath.substring(lastSlash + 1);
    }

    private static String parentDirectoryOf(String directoryPath) {
        int lastSlash = directoryPath.lastIndexOf('/');
        return lastSlash < 0 ? "" : directoryPath.substring(0, lastSlash);
    }

    private static String lastSegmentOf(String directoryPath) {
        int lastSlash = directoryPath.lastIndexOf('/');
        return lastSlash < 0 ? directoryPath : directoryPath.substring(lastSlash + 1);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    private static int segmentCount(String directoryPath) {
        return directoryPath.isEmpty() ? 0 : directoryPath.split("/").length;
    }

    private static List<String> segmentsOf(String directoryPath) {
        return directoryPath.isEmpty()
                ? List.of()
                : List.of(directoryPath.split("/"));
    }

    private static String joinSegments(List<String> segments, int count) {
        return String.join("/", segments.subList(0, count));
    }

    private record Relocation(Long scannedFileId, String oldPath, String newPath) {
    }

    private record DirectoryMove(String oldDirectory, String newDirectory) {
    }
}
