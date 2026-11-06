package com.premier.admin.report;

import com.premier.driver.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FareTransactionReportService {
    private final NamedParameterJdbcTemplate jdbc;
    private final VehicleRepository vehicleRepository;

    private static final String ROWS = """
            with report_rows as (
                select cast(t.id as varchar) as transaction_id,
                       'FARE_TRANSACTION' as record_source,
                       cast(t.passenger_id as varchar) as passenger_id,
                       p.card_number as passenger_identifier,
                       cast(null as varchar) as passenger_name,
                       coalesce(nullif(trim(t.vehicle_plate_number), ''), v.plate_number) as plate_number,
                       coalesce(t.offline_captured_at, t.created_at) as occurred_at,
                       coalesce(cast(t.payment_method as varchar), 'UNKNOWN') as payment_method,
                       abs(t.amount) as fare_amount,
                       cast(t.status as varchar) as status,
                       cast(t.trip_direction as varchar) as direction,
                       t.reference_number as reference_number,
                       coalesce(t.vehicle_id, v.id) as bus_id,
                       exists (select 1 from transactions reversal where reversal.reversal_of_id = t.id) as reversed
                  from transactions t
                  left join passengers p on p.id = t.passenger_id
                  left join vehicles v on v.id = t.vehicle_id
                 where t.type in ('FARE_DEDUCTION', 'RIDE_FARE')
                   and coalesce(t.offline_captured_at, t.created_at) >= :startAt
                   and coalesce(t.offline_captured_at, t.created_at) < :endExclusive
                   and (:vehicleId is null or t.vehicle_id = :vehicleId or v.id = :vehicleId)
                union all
                select cast(a.id as varchar) as transaction_id,
                       'FAILED_ATTEMPT' as record_source,
                       cast(a.passenger_id as varchar) as passenger_id,
                       p.card_number as passenger_identifier,
                       cast(null as varchar) as passenger_name,
                       coalesce(nullif(trim(a.vehicle_plate_number), ''), v.plate_number) as plate_number,
                       coalesce(a.offline_captured_at, a.created_at) as occurred_at,
                       coalesce(cast(a.payment_method as varchar), 'UNKNOWN') as payment_method,
                       coalesce(a.amount, 0) as fare_amount,
                       'FAILED' as status,
                       cast(a.trip_direction as varchar) as direction,
                       cast(null as varchar) as reference_number,
                       a.vehicle_id as bus_id,
                       false as reversed
                  from fare_payment_attempts a
                  left join passengers p on p.id = a.passenger_id
                  left join vehicles v on v.id = a.vehicle_id
                 where a.status = 'FAILED'
                   and a.transaction_id is null
                   and coalesce(a.offline_captured_at, a.created_at) >= :startAt
                   and coalesce(a.offline_captured_at, a.created_at) < :endExclusive
                   and (:vehicleId is null or a.vehicle_id = :vehicleId)
                union all
                select cast(c.id as varchar) as transaction_id,
                       'STAFF_CASH' as record_source,
                       cast(null as varchar) as passenger_id,
                       cast(null as varchar) as passenger_identifier,
                       cast(null as varchar) as passenger_name,
                       coalesce(nullif(trim(c.vehicle_plate_number), ''), v.plate_number) as plate_number,
                       coalesce(c.offline_captured_at, c.created_at) as occurred_at,
                       'ASSISTED_CASH' as payment_method,
                       c.final_fare as fare_amount,
                       'SUCCESS' as status,
                       cast(c.trip_direction as varchar) as direction,
                       c.reference_number as reference_number,
                       c.vehicle_id as bus_id,
                       false as reversed
                  from staff_cash_transactions c
                  left join vehicles v on v.id = c.vehicle_id
                 where coalesce(c.offline_captured_at, c.created_at) >= :startAt
                   and coalesce(c.offline_captured_at, c.created_at) < :endExclusive
                   and (:vehicleId is null or c.vehicle_id = :vehicleId)
            )
            , filtered_rows as (
                select * from report_rows
                 where (:status is null or upper(status) = :status)
                   and (:paymentMethod is null or upper(payment_method) = :paymentMethod)
            )
            """;

    public Map<String, Object> getReport(LocalDate startDate, LocalDate endDate, Long vehicleId,
                                         String status, String paymentMethod, int page, int size) {
        validate(startDate, endDate, vehicleId, status, paymentMethod, page, size);
        MapSqlParameterSource params = params(startDate, endDate, vehicleId, status, paymentMethod);
        long total = jdbc.queryForObject(ROWS + "select count(*) from filtered_rows", params, Long.class);
        List<Map<String, Object>> rows = jdbc.queryForList(ROWS + """
                select transaction_id, record_source, passenger_id, passenger_identifier, passenger_name, plate_number,
                       occurred_at, payment_method, fare_amount, status, direction, reference_number, reversed
                  from filtered_rows
                 order by occurred_at desc, record_source, transaction_id desc
                 limit :limit offset :offset
                """, params.addValue("limit", size).addValue("offset", (long) page * size));
        rows.forEach(row -> {
            Object at = row.get("occurred_at");
            if (at instanceof java.sql.Timestamp timestamp) row.put("occurred_at", timestamp.toLocalDateTime());
        });
        List<Map<String, Object>> grouped = jdbc.queryForList(ROWS + """
                select status, reversed, count(*) as transaction_count,
                       sum(case when status in ('SUCCESS', 'COMPLETED') and not reversed then fare_amount else 0 end) as successful_revenue
                  from filtered_rows group by status, reversed
                """, params);
        long successful = 0, failed = 0, pending = 0, cancelled = 0, reversed = 0;
        java.math.BigDecimal revenue = java.math.BigDecimal.ZERO;
        for (Map<String, Object> group : grouped) {
            String rowStatus = String.valueOf(group.get("status"));
            long count = ((Number) group.get("transaction_count")).longValue();
            boolean wasReversed = Boolean.TRUE.equals(group.get("reversed"));
            if (wasReversed) reversed += count;
            if (!wasReversed && (rowStatus.equals("SUCCESS") || rowStatus.equals("COMPLETED"))) successful += count;
            if (rowStatus.equals("FAILED") || rowStatus.equals("EXPIRED")) failed += count;
            if (rowStatus.equals("PENDING") || rowStatus.equals("PROCESSING")) pending += count;
            if (rowStatus.equals("CANCELLED")) cancelled += count;
            revenue = revenue.add((java.math.BigDecimal) group.get("successful_revenue"));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalCount", total);
        summary.put("successfulCount", successful);
        summary.put("failedCount", failed);
        summary.put("pendingCount", pending);
        summary.put("cancelledCount", cancelled);
        summary.put("reversedCount", reversed);
        summary.put("successfulRevenue", revenue);
        List<Map<String, Object>> paymentBreakdown = jdbc.queryForList(ROWS + """
                select payment_method, count(*) filter (where status in ('SUCCESS', 'COMPLETED') and not reversed) as transaction_count,
                       coalesce(sum(case when status in ('SUCCESS', 'COMPLETED') and not reversed then fare_amount else 0 end), 0) as successful_amount
                  from filtered_rows group by payment_method order by payment_method
                """, params);
        java.math.BigDecimal methodTotal = paymentBreakdown.stream()
                .map(item -> (java.math.BigDecimal) item.get("successful_amount"))
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        if (methodTotal.compareTo(revenue) != 0) throw new IllegalStateException("Payment method totals do not reconcile with successful fare revenue.");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startDate", startDate);
        result.put("endDate", endDate);
        result.put("vehicleId", vehicleId);
        result.put("status", status);
        result.put("paymentMethod", paymentMethod);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", total == 0 ? 0 : (total + size - 1) / size);
        result.put("summary", summary);
        result.put("paymentBreakdown", paymentBreakdown);
        result.put("transactions", rows);
        if (vehicleId == null) {
            result.put("busBreakdown", jdbc.queryForList(ROWS + """
                    select bus_id, plate_number, count(*) as transaction_count,
                           sum(case when status in ('SUCCESS', 'COMPLETED') and not reversed then fare_amount else 0 end) as successful_revenue
                      from filtered_rows group by bus_id, plate_number order by plate_number nulls last
                    """, params));
        }
        return result;
    }

    public List<Map<String, Object>> getVehicles() {
        return vehicleRepository.findAll(org.springframework.data.domain.Sort.by("plateNumber")).stream()
                .map(vehicle -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", vehicle.getId());
                    item.put("plateNumber", vehicle.getPlateNumber());
                    return item;
                }).toList();
    }

    private MapSqlParameterSource params(LocalDate startDate, LocalDate endDate, Long vehicleId, String status, String paymentMethod) {
        return new MapSqlParameterSource()
                .addValue("startAt", startDate.atStartOfDay())
                .addValue("endExclusive", endDate.plusDays(1).atStartOfDay())
                .addValue("vehicleId", vehicleId, Types.BIGINT)
                .addValue("status", status)
                .addValue("paymentMethod", paymentMethod);
    }

    private void validate(LocalDate startDate, LocalDate endDate, Long vehicleId, String status, String paymentMethod, int page, int size) {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("A valid report start and end date are required.");
        }
        if (endDate.isAfter(startDate.plusYears(10))) {
            throw new IllegalArgumentException("Report date ranges cannot exceed 10 years.");
        }
        if (vehicleId != null && !vehicleRepository.existsById(vehicleId)) {
            throw new IllegalArgumentException("Selected bus does not exist.");
        }
        if (page < 0 || size < 1 || size > 200) {
            throw new IllegalArgumentException("Invalid report page or size.");
        }
        if (status != null && !List.of("SUCCESS", "COMPLETED", "FAILED", "EXPIRED", "PENDING", "PROCESSING", "CANCELLED").contains(status)) throw new IllegalArgumentException("Invalid transaction status filter.");
        if (paymentMethod != null && !List.of("RFID", "NFC", "QR", "ADMIN", "PAYMONGO", "ASSISTED_CASH", "UNKNOWN").contains(paymentMethod)) throw new IllegalArgumentException("Invalid payment method filter.");
    }
}
