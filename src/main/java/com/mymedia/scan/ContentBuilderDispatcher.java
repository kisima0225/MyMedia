package com.mymedia.scan;

import com.mymedia.library.LibraryService;
import com.mymedia.scan.event.ScannedFileChanged;
import com.mymedia.scan.event.ScannedFileDiscovered;
import com.mymedia.scan.event.ScannedFileVanished;
import com.mymedia.scan.spi.LibraryContentBuilder;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把扫描事件按媒体库的 domain 分派给对应的领域构建器。
 *
 * <p>{@code scan} 只依赖 {@link LibraryContentBuilder} SPI，不知道具体领域实现。
 */
@Component
class ContentBuilderDispatcher {

    private final List<LibraryContentBuilder> builders;
    private final LibraryService libraryService;

    ContentBuilderDispatcher(List<LibraryContentBuilder> builders, LibraryService libraryService) {
        this.builders = builders;
        this.libraryService = libraryService;
    }

    @EventListener
    void on(ScannedFileDiscovered event) {
        for (LibraryContentBuilder builder : buildersFor(event.libraryId())) {
            builder.onFileDiscovered(event);
        }
    }

    @EventListener
    void on(ScannedFileChanged event) {
        for (LibraryContentBuilder builder : buildersFor(event.libraryId())) {
            builder.onFileChanged(event);
        }
    }

    @EventListener
    void on(ScannedFileVanished event) {
        for (LibraryContentBuilder builder : buildersFor(event.libraryId())) {
            builder.onFileVanished(event);
        }
    }

    private List<LibraryContentBuilder> buildersFor(Long libraryId) {
        var domain = libraryService.getById(libraryId).getDomain();
        return builders.stream()
                .filter(builder -> builder.supports(domain))
                .toList();
    }
}
