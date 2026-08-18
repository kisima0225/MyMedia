package com.mymedia.library;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface MediaLibraryRepository extends JpaRepository<MediaLibrary, Long> {

    List<MediaLibrary> findByDomain(LibraryDomain domain);
}
