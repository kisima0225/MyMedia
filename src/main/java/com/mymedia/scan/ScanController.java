package com.mymedia.scan;

import com.mymedia.library.LibraryService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/libraries")
class ScanController {

    private final ScanTrigger scanTrigger;
    private final LibraryService libraryService;

    ScanController(ScanTrigger scanTrigger, LibraryService libraryService) {
        this.scanTrigger = scanTrigger;
        this.libraryService = libraryService;
    }

    @PostMapping("/{id}/scan")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMIN')")
    Map<String, Object> requestScan(@PathVariable Long id) {
        libraryService.getById(id);
        Long jobId = scanTrigger.requestScan(id);
        return Map.of("jobId", jobId, "status", "ACCEPTED");
    }
}
