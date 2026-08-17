package com.mymedia.library;

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
@Table(name = "libraries")
public class MediaLibrary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8, updatable = false)
    private LibraryDomain domain;

    @Column(name = "root_path", nullable = false, unique = true)
    private String rootPath;

    @Column(name = "scan_cron", length = 64)
    private String scanCron;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected MediaLibrary() {
        // JPA 要求的无参构造器
    }

    MediaLibrary(String name, LibraryDomain domain, String rootPath) {
        this.name = name;
        this.domain = domain;
        this.rootPath = rootPath;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public LibraryDomain getDomain() { return domain; }
    public String getRootPath() { return rootPath; }
    public String getScanCron() { return scanCron; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }

    void rename(String newName) {
        this.name = newName;
    }
}
