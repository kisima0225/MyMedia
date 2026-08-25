package com.mymedia.video;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class VideoShareControllerTest extends AbstractIntegrationTest {

    private static final byte[] CONTENT = "0123456789abcdef".getBytes();

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
    private Long itemId;
    private Long fileId;
    private Long ownerId;

    @BeforeEach
    void setUp() throws Exception {
        Files.write(libraryRoot.resolve("movie.mp4"), CONTENT);
        library = libraryService.create("库" + UUID.randomUUID(), LibraryDomain.VIDEO,
                libraryRoot.toString());

        Long scannedId = jdbc.queryForObject("""
                INSERT INTO scanned_file (library_id, relative_path, size_bytes, mtime, extension)
                VALUES (?, 'movie.mp4', ?, now(), 'mp4') RETURNING id
                """, Long.class, library.getId(), (long) CONTENT.length);
        itemId = jdbc.queryForObject("""
                INSERT INTO video_item (library_id, item_type, title, sort_title)
                VALUES (?, 'MOVIE', '沙漠风暴', '沙漠风暴') RETURNING id
                """, Long.class, library.getId());
        fileId = jdbc.queryForObject("""
                INSERT INTO video_file (item_id, scanned_file_id, role)
                VALUES (?, ?, 'PRIMARY') RETURNING id
                """, Long.class, itemId, scannedId);

        UserAccount owner = registrationService.register(
                "u" + UUID.randomUUID().toString().substring(0, 8), "pw", UserRole.USER);
        ownerId = owner.getId();
    }

    private String share(String password) {
        return shareLinkService.createForVideoItem(ownerId, library.getId(), itemId,
                new ShareLinkDto.CreateRequest(password, null)).getToken();
    }

    @Test
    void anAnonymousVisitorCanReadTheItemAndStreamIt() throws Exception {
        String token = share(null);

        mockMvc.perform(get("/api/share/{token}/video/item", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.title").value("沙漠风暴"))
                .andExpect(jsonPath("$.files[0].id").value(fileId));

        mockMvc.perform(get("/api/share/{token}/video/stream/{fileId}", token, fileId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(content().bytes(CONTENT));
    }

    @Test
    void rangeRequestsWorkThroughTheShareEndpointToo() throws Exception {
        String token = share(null);

        mockMvc.perform(get("/api/share/{token}/video/stream/{fileId}", token, fileId)
                        .header(HttpHeaders.RANGE, "bytes=4-7"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 4-7/16"))
                .andExpect(content().bytes("4567".getBytes()));
    }

    @Test
    void aPasswordProtectedLinkNeedsATicket() throws Exception {
        String token = share("hunter2");

        mockMvc.perform(get("/api/share/{token}/video/item", token))
                .andExpect(status().isUnauthorized());

        // 但元信息端点必须能读——客户端正是靠它知道要弹密码框
        mockMvc.perform(get("/api/share/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresPassword").value(true))
                .andExpect(jsonPath("$.domain").value("VIDEO"));

        String unlock = mockMvc.perform(post("/api/share/{token}/unlock", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"hunter2\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String ticket = com.jayway.jsonpath.JsonPath.parse(unlock).read("$.ticket");

        mockMvc.perform(get("/api/share/{token}/video/item", token)
                        .header("X-Share-Ticket", ticket))
                .andExpect(status().isOk());
    }

    @Test
    void theWrongPasswordIsUnauthorizedAndNoTicketComesBack() throws Exception {
        String token = share("hunter2");

        mockMvc.perform(post("/api/share/{token}/unlock", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aForgedTicketDoesNotWork() throws Exception {
        String token = share("hunter2");

        mockMvc.perform(get("/api/share/{token}/video/item", token)
                        .header("X-Share-Ticket", "99999999999.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRevokedLinkStopsWorkingImmediately() throws Exception {
        String token = share(null);
        Long shareId = jdbc.queryForObject(
                "SELECT id FROM share_link WHERE token = ?", Long.class, token);
        shareLinkService.revoke(ownerId, shareId);

        mockMvc.perform(get("/api/share/{token}/video/item", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void anExpiredLinkStopsWorking() throws Exception {
        String token = share(null);
        jdbc.update("UPDATE share_link SET expires_at = ? WHERE token = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), token);

        mockMvc.perform(get("/api/share/{token}/video/item", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void aLinkForOneItemCannotStreamAnotherItemsFile() throws Exception {
        Long otherItem = jdbc.queryForObject("""
                INSERT INTO video_item (library_id, item_type, title, sort_title)
                VALUES (?, 'MOVIE', '雪原突击', '雪原突击') RETURNING id
                """, Long.class, library.getId());
        Long otherScanned = jdbc.queryForObject("""
                INSERT INTO scanned_file (library_id, relative_path, size_bytes, mtime, extension)
                VALUES (?, 'other.mp4', 16, now(), 'mp4') RETURNING id
                """, Long.class, library.getId());
        Long otherFile = jdbc.queryForObject("""
                INSERT INTO video_file (item_id, scanned_file_id, role)
                VALUES (?, ?, 'PRIMARY') RETURNING id
                """, Long.class, otherItem, otherScanned);

        String token = share(null);

        mockMvc.perform(get("/api/share/{token}/video/stream/{fileId}", token, otherFile))
                .andExpect(status().isNotFound());
    }

    @Test
    void theManagementEndpointIsStillBehindLogin() throws Exception {
        // /api/share/** 是 permitAll，/api/shares 不是。
        // 路径模式按整段比较，两者不会互相匹配——这一条必须钉住。
        mockMvc.perform(get("/api/shares"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownTokenIsNotFound() throws Exception {
        mockMvc.perform(get("/api/share/{token}/video/item",
                        "Zm9vYmFyLXNvbWUtb3RoZXItc2hhcmUtdG9rZW4tMTIz"))
                .andExpect(status().isNotFound());
    }
}
