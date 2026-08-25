package com.mymedia.metadata.web;

import com.mymedia.library.LibraryDomain;
import com.mymedia.metadata.TagService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签本身的管理。
 *
 * <p>标签是**全库共享的词表**，不属于任何一个媒体库，因此建与删限 ADMIN；
 * 列出对所有登录用户开放（不列出会让打标签的下拉框没法填）。
 */
@RestController
@RequestMapping("/api/tags")
class TagController {

    private final TagService tagService;

    TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    List<TagDto.Response> list(@RequestParam LibraryDomain domain) {
        return tagService.findByDomain(domain).stream().map(TagDto.Response::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    TagDto.Response create(@Valid @RequestBody TagDto.CreateRequest request) {
        // TagSlug.of 对全标点的名字抛 IllegalArgumentException → GlobalExceptionHandler 翻成 400
        return TagDto.Response.from(tagService.findOrCreate(request.domain(), request.name()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    void delete(@PathVariable Long id) {
        tagService.delete(id);
    }
}
