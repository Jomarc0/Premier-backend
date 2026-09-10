package com.premier.staffcash.repository;
import com.premier.staffcash.model.StaffCashRemittanceAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.math.BigDecimal;
import java.util.*;
public interface StaffCashRemittanceAdjustmentRepository extends JpaRepository<StaffCashRemittanceAdjustment,Long> {
    Optional<StaffCashRemittanceAdjustment> findByRequestId(String requestId);
    @Query("select coalesce(sum(a.amount),0) from StaffCashRemittanceAdjustment a where a.remittanceId = :remittanceId")
    BigDecimal total(Long remittanceId);
    List<StaffCashRemittanceAdjustment> findByRemittanceIdOrderByIdAsc(Long remittanceId);
}
