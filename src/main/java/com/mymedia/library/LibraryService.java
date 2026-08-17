package com.mymedia.library;

import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class LibraryService {

    private final MediaLibraryRepository repository;

    LibraryService(MediaLibraryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public MediaLibrary create(String name, LibraryDomain domain, String rootPath) {
        return repository.saveAndFlush(new MediaLibrary(name, domain, rootPath));
    }

    @Transactional(readOnly = true)
    public MediaLibrary getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到媒体库 id=" + id));
    }

    @Transactional(readOnly = true)
    public List<MediaLibrary> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public List<MediaLibrary> findByDomain(LibraryDomain domain) {
        return repository.findByDomain(domain);
    }
}
