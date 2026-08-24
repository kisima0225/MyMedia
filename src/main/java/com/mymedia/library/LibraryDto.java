package com.mymedia.library;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

final class LibraryDto {

    private LibraryDto() {
    }

    record CreateRequest(
            @NotBlank @Size(max = 128) String name,
            @NotNull LibraryDomain domain,
            @NotBlank String rootPath) {
    }

    record MetadataProvidersRequest(@NotNull List<String> providers) {
    }

    record Response(
            Long id,
            String name,
            LibraryDomain domain,
            String rootPath,
            boolean enabled) {

        static Response from(MediaLibrary library) {
            return new Response(
                    library.getId(),
                    library.getName(),
                    library.getDomain(),
                    library.getRootPath(),
                    library.isEnabled());
        }
    }
}
