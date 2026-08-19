package com.mymedia.video;

import com.mymedia.shared.MaterializedPath;
import com.mymedia.shared.NaturalSortKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * 视频库的目录树浏览视图。
 *
 * <p><b>这是派生索引，不是主模型。</b>视频域的主浏览方式是语义化的
 * （按电影 / 剧集 / 合集）；本表只让用户能按自己的目录组织方式导航，
 * 不承载元数据与观看进度。
 */
@Entity
@Table(name = "video_folder")
public class VideoFolder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "library_id", nullable = false, updatable = false)
    private Long libraryId;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "materialized_path", nullable = false)
    private String materializedPath;

    @Column(nullable = false)
    private int depth;

    @Column(nullable = false)
    private String name;

    @Column(name = "sort_key", nullable = false)
    private String sortKey;

    @Column(name = "direct_item_count", nullable = false)
    private int directItemCount = 0;

    @Column(name = "total_item_count", nullable = false)
    private int totalItemCount = 0;

    protected VideoFolder() {
    }

    VideoFolder(Long libraryId, Long parentId, String parentPath, String name) {
        this.libraryId = libraryId;
        this.parentId = parentId;
        this.name = name;
        this.sortKey = NaturalSortKey.of(name);

        // INSERT 前没有 id；父路径本身会撞上唯一键，故使用唯一临时段，之后由 indexer 最终化。
        this.materializedPath = parentPath + "tmp-" + UUID.randomUUID() + "/";
        this.depth = MaterializedPath.depthOf(parentPath) + 1;
    }

    public Long getId() { return id; }
    public Long getLibraryId() { return libraryId; }
    public Long getParentId() { return parentId; }
    public String getMaterializedPath() { return materializedPath; }
    public int getDepth() { return depth; }
    public String getName() { return name; }
    public String getSortKey() { return sortKey; }
    public int getDirectItemCount() { return directItemCount; }
    public int getTotalItemCount() { return totalItemCount; }

    /**
     * 插入拿到 id 之后补全自身路径。物化路径包含自己的 id，
     * 因此必须在 INSERT 之后才能确定。
     */
    void finalizePath(String parentPath) {
        this.materializedPath = MaterializedPath.childOf(parentPath, this.id);
        this.depth = MaterializedPath.depthOf(this.materializedPath);
    }

    void setCounts(int direct, int total) {
        this.directItemCount = direct;
        this.totalItemCount = total;
    }
}
