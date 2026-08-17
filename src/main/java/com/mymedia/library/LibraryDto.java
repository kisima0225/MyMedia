package com.mymedia.library;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class LibraryDto {

    private LibraryDto() {
    }

    public record CreateRequest(
            @NotBlank String name,
            @NotNull LibraryDomain domain,
            @NotBlank String rootPath) {
    }

    public record Response(
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
