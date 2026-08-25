package com.mymedia.image;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.library.MediaLibrary;
import com.mymedia.library.ShareLinkDto;
import com.mymedia.library.ShareLinkService;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ImageShareControllerTest extends AbstractIntegrationTest {

    private static final byte[] PIXEL = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};

    @Autowired
    MockMvc mockMvc;

    @Autowired
    LibraryService libraryService;

    @Autowired
    ShareLinkService shareLinkService;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path libraryRoot;

    private MediaLibrary library;
    private Long artistId;
    private Long bookId;
    private Long pageId;
    private Long outsiderPageId;
    private Long ownerId;

    /** 建一个节点；parentPath 为 null 表示建在根上。 */
    private Long insertNode(Long parentId, String parentPath, String name, int directPageCount) {
        Long id = jdbc.queryForObject("""
                INSERT INTO image_node (library_id, materialized_path, sort_path, depth,
                                        parent_id, name, sort_key, source_kind,
                                        direct_page_count, status)
                VALUES (?, '', '/' || ? || '/', ?, ?, ?, ?, 'DIRECTORY', ?, 'ACTIVE')
                RETURNING id
                """, Long.class, library.getId(), name,
                parentPath == null ? 0 : 1, parentId, name, name, directPageCount);
        // 物化路径要含自己的 id，所以只能插完再回填（与计划 04 的建树逻辑一致）
        String path = (parentPath == null ? "/" : parentPath) + id + "/";
        jdbc.update("UPDATE image_node SET materialized_path = ? WHERE id = ?", path, id);
        return id;
    }

    private String pathOf(Long nodeId) {
        return jdbc.queryForObject(
                "SELECT materialized_path FROM image_node WHERE id = ?", String.class, nodeId);
    }

    private Long insertPage(Long nodeId, String fileName, int pageIndex) throws Exception {
        Files.write(libraryRoot.resolve(fileName), PIXEL);
        Long scannedId = jdbc.queryForObject("""
                INSERT INTO scanned_file (library_id, relative_path, size_bytes, mtime, extension)
                VALUES (?, ?, ?, now(), 'jpg') RETURNING id
                """, Long.class, library.getId(), fileName, (long) PIXEL.length);
        return jdbc.queryForObject("""
                INSERT INTO image_file (node_id, scanned_file_id, page_index, sort_key)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, nodeId, scannedId, pageIndex, fileName);
    }

    @BeforeEach
    void setUp() throws Exception {
        library = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.IMAGE,
                libraryRoot.toString());

        artistId = insertNode(null, null, "某画师", 0);
        bookId = insertNode(artistId, pathOf(artistId), "第一本", 1);
        pageId = insertPage(bookId, "p001.jpg", 0);

        Long outsider = insertNode(null, null, "别人", 1);
        outsiderPageId = insertPage(outsider, "x001.jpg", 0);

        UserAccount owner = registrationService.register(
                "u" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        ownerId = owner.getId();
    }

    private String share(Long nodeId) {
        return shareLinkService.createForImageNode(ownerId, library.getId(), nodeId,
                new ShareLinkDto.CreateRequest(null, null)).getToken();
    }

    @Test
    void sharingAFolderExposesItsChildren() throws Exception {
        String token = share(artistId);

        mockMvc.perform(get("/api/share/{token}/image/node", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.node.name").value("某画师"))
                .andExpect(jsonPath("$.children", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.children[0].id").value(bookId))
                .andExpect(jsonPath("$.pages", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    void navigatingIntoAChildWithinTheSharedSubtreeIsAllowed() throws Exception {
        String token = share(artistId);

        mockMvc.perform(get("/api/share/{token}/image/node", token).param("nodeId", bookId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.pages[0].id").value(pageId));
    }

    @Test
    void aPageInsideTheSharedSubtreeCanBeRead() throws Exception {
        String token = share(artistId);

        mockMvc.perform(get("/api/share/{token}/image/pages/{fileId}", token, pageId))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PIXEL));
    }

    @Test
    void nodesOutsideTheSharedSubtreeAreNotFound() throws Exception {
        String token = share(bookId);

        mockMvc.perform(get("/api/share/{token}/image/node", token)
                        .param("nodeId", artistId.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void pagesOutsideTheSharedSubtreeAreNotFound() throws Exception {
        String token = share(artistId);

        mockMvc.perform(get("/api/share/{token}/image/pages/{fileId}", token, outsiderPageId))
                .andExpect(status().isNotFound());
    }

    @Test
    void animageTokenCannotBeUsedOnTheVideoEndpoints() throws Exception {
        String token = share(artistId);

        mockMvc.perform(get("/api/share/{token}/video/item", token))
                .andExpect(status().isNotFound());
    }
}
