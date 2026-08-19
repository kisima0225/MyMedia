package com.mymedia.video;

import com.mymedia.shared.NaturalSortKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "video_group")
public class VideoGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false, updatable = false)
    private Long itemId;

    @Column(name = "group_index", nullable = false)
    private int groupIndex;

    @Column(nullable = false)
    private String name;

    @Column(name = "sort_key", nullable = false)
    private String sortKey;

    @Column
    private String summary;

    protected VideoGroup() {
    }

    VideoGroup(Long itemId, int groupIndex, String name) {
        this.itemId = itemId;
        this.groupIndex = groupIndex;
        this.name = name;
        this.sortKey = NaturalSortKey.of(name);
    }

    public Long getId() { return id; }
    public Long getItemId() { return itemId; }
    public int getGroupIndex() { return groupIndex; }
    public String getName() { return name; }
    public String getSortKey() { return sortKey; }
}
