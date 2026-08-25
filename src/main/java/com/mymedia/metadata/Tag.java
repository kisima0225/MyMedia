package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "tag")
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8, updatable = false)
    private LibraryDomain domain;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private String slug;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Tag() {
        // JPA 要求的无参构造器
    }

    Tag(LibraryDomain domain, String name, String slug) {
        this.domain = domain;
        this.name = name;
        this.slug = slug;
    }

    public Long getId() { return id; }
    public LibraryDomain getDomain() { return domain; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public Instant getCreatedAt() { return createdAt; }
}
