package com.mymedia.metadata.web;

import com.mymedia.library.LibraryDomain;
import com.mymedia.metadata.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

final class TagDto {

    private TagDto() {
    }

    record CreateRequest(@NotNull LibraryDomain domain,
                         @NotBlank @Size(max = 64) String name) {
    }

    record Response(Long id, LibraryDomain domain, String name, String slug) {

        static Response from(Tag tag) {
            return new Response(tag.getId(), tag.getDomain(), tag.getName(), tag.getSlug());
        }
    }

    record SetTagsRequest(@NotNull List<Long> tagIds) {
    }

    /** 按标签浏览的结果。两个域共用一个形状——它只需要够画一张卡片。 */
    record TaggedTarget(Long id, String title, Long coverAssetId) {
    }
}
