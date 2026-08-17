package com.mymedia.library;

import com.mymedia.user.UserAccount;
import com.mymedia.user.UserQueryService;
import com.mymedia.user.UserRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class LibraryAccessService {

    private final JdbcTemplate jdbc;
    private final UserQueryService userQueryService;
    private final MediaLibraryRepository libraryRepository;

    LibraryAccessService(JdbcTemplate jdbc,
                         UserQueryService userQueryService,
                         MediaLibraryRepository libraryRepository) {
        this.jdbc = jdbc;
        this.userQueryService = userQueryService;
        this.libraryRepository = libraryRepository;
    }

    @Transactional(readOnly = true)
    public boolean canAccess(Long userId, Long libraryId) {
        if (isAdmin(userId)) {
            return true;
        }
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM library_access WHERE user_id = ? AND library_id = ?",
                Integer.class, userId, libraryId);
        return count != null && count > 0;
    }

    @Transactional
    public void grant(Long userId, Long libraryId) {
        // ON CONFLICT DO NOTHING 使重复授权成为幂等操作
        jdbc.update("""
                INSERT INTO library_access (user_id, library_id) VALUES (?, ?)
                ON CONFLICT (user_id, library_id) DO NOTHING
                """, userId, libraryId);
    }

    @Transactional
    public void revoke(Long userId, Long libraryId) {
        jdbc.update("DELETE FROM library_access WHERE user_id = ? AND library_id = ?",
                userId, libraryId);
    }

    @Transactional(readOnly = true)
    public List<MediaLibrary> accessibleLibraries(Long userId) {
        if (isAdmin(userId)) {
            return libraryRepository.findAll();
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT library_id FROM library_access WHERE user_id = ?", Long.class, userId);
        return ids.isEmpty() ? List.of() : libraryRepository.findAllById(ids);
    }

    private boolean isAdmin(Long userId) {
        UserAccount account = userQueryService.getById(userId);
        return account.getRole() == UserRole.ADMIN;
    }
}
