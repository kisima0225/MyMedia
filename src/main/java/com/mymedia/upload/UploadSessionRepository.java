package com.mymedia.upload;

import org.springframework.data.jpa.repository.JpaRepository;

interface UploadSessionRepository extends JpaRepository<UploadSession, Long> {
}
