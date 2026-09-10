package com.premier.driver.repository;

import com.premier.driver.model.DriverShiftCode;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface DriverShiftCodeRepository extends JpaRepository<DriverShiftCode, Long> {
    @EntityGraph(attributePaths = {"assignment", "driver", "vehicle"})
    Optional<DriverShiftCode> findByCodeHash(String codeHash);

    @Modifying
    @Query("update DriverShiftCode c set c.usedAt = :now where c.assignment.id = :assignmentId and c.usedAt is null and c.expiresAt > :now")
    int expireActiveForAssignment(@Param("assignmentId") Long assignmentId, @Param("now") LocalDateTime now);
}
