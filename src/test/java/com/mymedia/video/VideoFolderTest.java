package com.mymedia.video;

import com.mymedia.shared.MaterializedPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class VideoFolderTest {

    @Test
    void createsUniqueTemporaryPathsBeforeDatabaseIdsExist() {
        VideoFolder first = new VideoFolder(1L, null, MaterializedPath.rootPath(), "电影");
        VideoFolder second = new VideoFolder(1L, null, MaterializedPath.rootPath(), "剧集");

        assertThat(first.getMaterializedPath())
                .startsWith("/tmp-")
                .endsWith("/")
                .isNotEqualTo(MaterializedPath.rootPath());
        assertThat(second.getMaterializedPath()).isNotEqualTo(first.getMaterializedPath());
        assertThat(first.getDepth()).isEqualTo(1);
    }

    @Test
    void finalizesToNumericChildPathWithMatchingDepth() {
        VideoFolder folder = new VideoFolder(1L, 10L, "/10/", "电影");
        ReflectionTestUtils.setField(folder, "id", 42L);

        folder.finalizePath("/10/");

        assertThat(folder.getMaterializedPath()).isEqualTo("/10/42/");
        assertThat(folder.getDepth()).isEqualTo(2);
    }
}
