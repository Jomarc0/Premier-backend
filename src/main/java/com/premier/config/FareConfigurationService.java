package com.premier.config;

import com.premier.admin.model.Admin;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service @RequiredArgsConstructor
public class FareConfigurationService {
    private final FareConfigurationRepository repository;
    private final FareConfigurationNotificationService fareNotifications;

    @Transactional(readOnly=true)
    public FareConfiguration getActive() {
        return repository.findActiveForFareCalculation().orElseThrow(() -> new IllegalStateException("Fare configuration has not been initialized. Apply the database migration."));
    }

    @Transactional(readOnly=true)
    public FareConfigurationDto getActiveDto() {
        FareConfiguration configuration = repository.findById(1L).orElseThrow(() ->
                new IllegalStateException("Fare configuration has not been initialized. Apply the database migration."));
        return toDto(configuration);
    }

    @Transactional
    public FareConfiguration update(BigDecimal regular, BigDecimal discounted, Long expectedVersion, Admin admin) {
        validate(regular, "Regular fare"); validate(discounted, "Discounted fare");
        if (discounted.compareTo(regular) >= 0) throw new com.premier.exception.ClientException(HttpStatus.BAD_REQUEST,"INVALID_FARE","Discounted fare must be less than regular fare.");
        FareConfiguration current=repository.findActiveForUpdate().orElseThrow(() -> new IllegalStateException("Fare configuration has not been initialized. Apply the database migration."));
        if (expectedVersion == null || !expectedVersion.equals(current.getVersion())) throw new com.premier.exception.ClientException(HttpStatus.CONFLICT,"FARE_CONFIGURATION_CHANGED","Fare configuration changed. Reload the current values and try again.");
        boolean changed = current.getRegularFare().compareTo(regular) != 0 || current.getDiscountedFare().compareTo(discounted) != 0;
        current.setRegularFare(regular); current.setDiscountedFare(discounted); current.setUpdatedBy(admin);
        FareConfiguration saved = repository.saveAndFlush(current);
        if (changed) {
            Runnable dispatch = () -> fareNotifications.notifyAllPassengers(saved.getRegularFare(), saved.getDiscountedFare(), saved.getVersion());
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                    && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void afterCommit() { dispatch.run(); }
                        });
            } else {
                dispatch.run();
            }
        }
        return saved;
    }

    @Transactional
    public FareConfigurationDto updateAndReturnDto(BigDecimal regular, BigDecimal discounted, Long expectedVersion, Admin admin) {
        return toDto(update(regular, discounted, expectedVersion, admin));
    }
    private void validate(BigDecimal amount, String label) {
        if (amount==null || amount.scale()>2 || amount.precision()>10 || amount.compareTo(BigDecimal.ZERO)<0 || amount.compareTo(new BigDecimal("100000.00"))>0)
            throw new com.premier.exception.ClientException(HttpStatus.BAD_REQUEST,"INVALID_FARE",label+" must be between PHP 0.00 and PHP 100,000.00 with at most two decimal places.");
    }
    public FareConfigurationDto toDto(FareConfiguration f) {
        return new FareConfigurationDto(f.getRegularFare(),f.getDiscountedFare(),f.getCurrency(),f.getVersion(),f.getUpdatedAt(),f.getUpdatedBy()==null?null:f.getUpdatedBy().getFullName());
    }
    public record FareConfigurationDto(BigDecimal regularFare, BigDecimal discountedFare, String currency, Long version, LocalDateTime updatedAt, String updatedBy) {}
}
