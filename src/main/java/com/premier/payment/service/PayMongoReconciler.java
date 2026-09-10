package com.premier.payment.service;

import com.premier.model.TransactionStatus;
import com.premier.repository.TopUpRequestRepository;
import com.premier.service.PayMongoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Opt-in after staging verification. GET only; never repeats an unknown provider POST. */
@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name = "paymongo.reconciliation.enabled", havingValue = "true")
public class PayMongoReconciler {
    private final TopUpRequestRepository requests;
    private final PayMongoService paymongo;
    private long cursor;

    @Scheduled(fixedDelayString = "${paymongo.reconciliation.delay-ms:60000}", initialDelayString = "${paymongo.reconciliation.delay-ms:60000}")
    public synchronized void reconcileBatch() {
        var batch = requests.findByStatusAndPaymongoLinkIdIsNotNullAndIdGreaterThanOrderByIdAsc(
                TransactionStatus.PENDING, cursor, PageRequest.of(0, 10));
        if (batch.isEmpty()) { cursor = 0; return; }
        for (var request : batch) {
            cursor = request.getId();
            try { paymongo.reconcile(request.getId()); }
            catch (RuntimeException ex) { log.warn("PAYMENT_RECONCILIATION_PENDING reference={} type={}", request.getReferenceNumber(), ex.getClass().getSimpleName()); }
        }
    }
}
