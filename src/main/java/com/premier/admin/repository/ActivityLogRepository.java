package com.premier.admin.repository;

import com.premier.admin.model.ActivityLog;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDateTime;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {
    Page<ActivityLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT COUNT(a) FROM ActivityLog a WHERE a.createdAt >= :today")
    long countTodayLogs(LocalDateTime today);

    @Query("SELECT COUNT(DISTINCT a.admin.id) FROM ActivityLog a")
    long countUniqueAdmins();
}