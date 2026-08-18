package com.mymedia.library;

import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserQueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/libraries")
class LibraryController {

    private final LibraryService libraryService;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    LibraryController(LibraryService libraryService,
                      LibraryAccessService accessService,
                      UserQueryService userQueryService) {
        this.libraryService = libraryService;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @GetMapping
    List<LibraryDto.Response> list(@AuthenticationPrincipal UserDetails principal) {
        Long userId = currentUserId(principal);
        return accessService.accessibleLibraries(userId).stream()
                .map(LibraryDto.Response::from)
                .toList();
    }

    @GetMapping("/{id}")
    LibraryDto.Response getOne(@AuthenticationPrincipal UserDetails principal,
                               @PathVariable Long id) {
        Long userId = currentUserId(principal);
        if (!accessService.canAccess(userId, id)) {
            // 返回 404 而非 403：不向无权访问者泄露资源是否存在
            throw new NotFoundException("找不到媒体库 id=" + id);
        }
        return LibraryDto.Response.from(libraryService.getById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    LibraryDto.Response create(@Valid @RequestBody LibraryDto.CreateRequest request) {
        MediaLibrary library = libraryService.create(
                request.name(), request.domain(), request.rootPath());
        return LibraryDto.Response.from(library);
    }

    private Long currentUserId(UserDetails principal) {
        UserAccount account = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户: " + principal.getUsername()));
        return account.getId();
    }
}
