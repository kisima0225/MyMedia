package com.mymedia.video.web;

import com.mymedia.library.LibraryAccessService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.MediaLibrary;
import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserQueryService;
import com.mymedia.video.VideoCatalogService;
import com.mymedia.video.VideoFile;
import com.mymedia.video.VideoFileRole;
import com.mymedia.video.VideoGroup;
import com.mymedia.video.VideoItem;
import com.mymedia.video.VideoItemType;
import com.mymedia.video.VideoStructure;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoCatalogControllerUnitTest {

    private static final Long USER_ID = 7L;
    private static final UserDetails PRINCIPAL = User.withUsername("user")
            .password("pw")
            .roles("USER")
            .build();

    @Test
    void dtoMapsItemGroupAndFileFields() {
        VideoItem item = mock(VideoItem.class);
        when(item.getId()).thenReturn(11L);
        when(item.getTitle()).thenReturn("电影");
        when(item.getItemType()).thenReturn(VideoItemType.MOVIE);
        when(item.getStructure()).thenReturn(VideoStructure.GROUPED);

        VideoGroup group = mock(VideoGroup.class);
        when(group.getId()).thenReturn(12L);
        when(group.getGroupIndex()).thenReturn(2);
        when(group.getName()).thenReturn("第二季");

        VideoFile file = mock(VideoFile.class);
        when(file.getId()).thenReturn(13L);
        when(file.getGroupId()).thenReturn(12L);
        when(file.getRole()).thenReturn(VideoFileRole.PRIMARY);
        when(file.getEpisodeIndex()).thenReturn(3);
        when(file.getDurationSeconds()).thenReturn(120);
        when(file.getWidth()).thenReturn(1920);
        when(file.getHeight()).thenReturn(1080);

        // getCoverAssetId()/getLibraryId() 未被 stub，Mockito 对 Long 等数值包装类型的
        // 默认返回值是 0 而不是 null（与集合类型的空默认值同理）。
        assertThat(VideoCatalogDto.ItemSummary.from(item))
                .isEqualTo(new VideoCatalogDto.ItemSummary(11L, "电影", "MOVIE", "GROUPED", 0L, 0L));
        assertThat(VideoCatalogDto.GroupSummary.from(group))
                .isEqualTo(new VideoCatalogDto.GroupSummary(12L, 2, "第二季"));
        assertThat(VideoCatalogDto.FileSummary.from(file))
                .isEqualTo(new VideoCatalogDto.FileSummary(13L, 12L, "PRIMARY", 3, 120, 1920, 1080));
    }

    @Test
    void listUsesOnlyAccessibleVideoLibraries() {
        VideoCatalogService catalogService = mock(VideoCatalogService.class);
        LibraryAccessService accessService = mock(LibraryAccessService.class);
        UserQueryService userQueryService = userQueryService();
        VideoCatalogController controller = new VideoCatalogController(
                catalogService, accessService, userQueryService);

        MediaLibrary videoLibrary = mock(MediaLibrary.class);
        when(videoLibrary.getId()).thenReturn(21L);
        when(videoLibrary.getDomain()).thenReturn(LibraryDomain.VIDEO);
        MediaLibrary imageLibrary = mock(MediaLibrary.class);
        when(imageLibrary.getId()).thenReturn(22L);
        when(imageLibrary.getDomain()).thenReturn(LibraryDomain.IMAGE);

        VideoItem item = item(31L, 21L, "可见条目");
        when(accessService.accessibleLibraries(USER_ID))
                .thenReturn(List.of(videoLibrary, imageLibrary));
        when(catalogService.findByLibrary(21L)).thenReturn(List.of(item));

        // coverAssetId 未被 stub，同样默认为 0（Mockito 对 Long 的默认返回值）。
        assertThat(controller.list(PRINCIPAL))
                .containsExactly(new VideoCatalogDto.ItemSummary(31L, "可见条目", "MOVIE", "FLAT", 0L, 21L));
    }

    @Test
    void detailReturns404WhenLibraryIsNotAccessible() {
        VideoCatalogService catalogService = mock(VideoCatalogService.class);
        LibraryAccessService accessService = mock(LibraryAccessService.class);
        UserQueryService userQueryService = userQueryService();
        VideoCatalogController controller = new VideoCatalogController(
                catalogService, accessService, userQueryService);
        VideoItem item = item(41L, 42L, "隐藏条目");
        when(catalogService.getItem(41L)).thenReturn(item);
        when(accessService.canAccess(USER_ID, 42L)).thenReturn(false);

        assertThatThrownBy(() -> controller.detail(PRINCIPAL, 41L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void episodesReturns404WhenLibraryIsNotAccessible() {
        VideoCatalogService catalogService = mock(VideoCatalogService.class);
        LibraryAccessService accessService = mock(LibraryAccessService.class);
        UserQueryService userQueryService = userQueryService();
        VideoCatalogController controller = new VideoCatalogController(
                catalogService, accessService, userQueryService);
        VideoItem item = item(51L, 52L, "隐藏剧集");
        when(catalogService.getItem(51L)).thenReturn(item);
        when(accessService.canAccess(USER_ID, 52L)).thenReturn(false);

        assertThatThrownBy(() -> controller.episodes(PRINCIPAL, 51L))
                .isInstanceOf(NotFoundException.class);
    }

    private static VideoItem item(Long id, Long libraryId, String title) {
        VideoItem item = mock(VideoItem.class);
        when(item.getId()).thenReturn(id);
        when(item.getLibraryId()).thenReturn(libraryId);
        when(item.getTitle()).thenReturn(title);
        when(item.getItemType()).thenReturn(VideoItemType.MOVIE);
        when(item.getStructure()).thenReturn(VideoStructure.FLAT);
        return item;
    }

    private static UserQueryService userQueryService() {
        UserQueryService service = mock(UserQueryService.class);
        UserAccount account = mock(UserAccount.class);
        when(account.getId()).thenReturn(USER_ID);
        when(service.findByUsername("user")).thenReturn(Optional.of(account));
        return service;
    }
}
