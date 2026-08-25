package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface TagRepository extends JpaRepository<Tag, Long> {

    Optional<Tag> findByDomainAndSlug(LibraryDomain domain, String slug);

    List<Tag> findByDomainOrderByName(LibraryDomain domain);
}
