package com.premier.staffqueue.repository;

import com.premier.staffqueue.model.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface TerminalQueueEntryRepository extends JpaRepository<TerminalQueueEntry, Long> {
    @EntityGraph(attributePaths = {"vehicle", "checkedInBy"})
    List<TerminalQueueEntry> findByStatusInOrderByCheckedInAtAscIdAsc(Collection<TerminalQueueStatus> statuses);

    Optional<TerminalQueueEntry> findByVehicleIdAndStatusIn(Long vehicleId, Collection<TerminalQueueStatus> statuses);

    boolean existsByVehicleIdAndStatusIn(Long vehicleId, Collection<TerminalQueueStatus> statuses);

    boolean existsByTerminalAndStatus(TerminalCode terminal, TerminalQueueStatus status);

    Optional<TerminalQueueEntry> findTopByVehicleIdOrderByCheckedInAtDescIdDesc(Long vehicleId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from TerminalQueueEntry q join fetch q.vehicle where q.id = :id")
    Optional<TerminalQueueEntry> findLockedById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TerminalQueueEntry> findFirstByTerminalAndStatusOrderByCheckedInAtAscIdAsc(
            TerminalCode terminal, TerminalQueueStatus status);
}
