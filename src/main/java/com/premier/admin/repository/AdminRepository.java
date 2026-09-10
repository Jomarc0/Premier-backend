package com.premier.admin.repository;

import com.premier.admin.model.Admin;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AdminRepository extends JpaRepository<Admin, Long> {
    Optional<Admin> findByUsername(String username);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from Admin a where a.id = :id")
    Optional<Admin> findLockedById(@org.springframework.data.repository.query.Param("id") Long id);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from Admin a where a.username = :username")
    Optional<Admin> findLockedByUsername(@org.springframework.data.repository.query.Param("username") String username);
    boolean existsByUsername(String username);
}
