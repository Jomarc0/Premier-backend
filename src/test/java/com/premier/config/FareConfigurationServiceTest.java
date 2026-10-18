package com.premier.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FareConfigurationServiceTest {
    private FareConfigurationService service(FareConfigurationRepository repository) {
        return new FareConfigurationService(repository, mock(com.premier.repository.PassengerFcmTokenRepository.class),
                mock(com.premier.service.FirebaseService.class));
    }

    @Test void updatesBothFaresAgainstTheCurrentVersion() {
        FareConfiguration current = FareConfiguration.builder().id(1L).regularFare(new BigDecimal("75.00"))
                .discountedFare(new BigDecimal("60.00")).version(4L).build();
        FareConfigurationRepository repository = mock(FareConfigurationRepository.class);
        when(repository.findActiveForUpdate()).thenReturn(Optional.of(current));
        when(repository.saveAndFlush(current)).thenReturn(current);

        FareConfiguration saved = service(repository).update(new BigDecimal("80.00"), new BigDecimal("64.00"), 4L, null);

        assertEquals(new BigDecimal("80.00"), saved.getRegularFare());
        assertEquals(new BigDecimal("64.00"), saved.getDiscountedFare());
        verify(repository).saveAndFlush(current);
    }

    @Test void rejectsNegativeAndMalformedFareAmounts() {
        FareConfigurationRepository repository = mock(FareConfigurationRepository.class);
        var service = service(repository);
        var negative = assertThrows(com.premier.exception.ClientException.class,
                () -> service.update(new BigDecimal("-1.00"), new BigDecimal("0.50"), 0L, null));
        assertEquals(HttpStatus.BAD_REQUEST, negative.getStatus());
        var tooPrecise = assertThrows(com.premier.exception.ClientException.class,
                () -> service.update(new BigDecimal("10.001"), new BigDecimal("5.00"), 0L, null));
        assertEquals(HttpStatus.BAD_REQUEST, tooPrecise.getStatus());
        verifyNoInteractions(repository);
    }

    @Test void rejectsInvalidDiscountRelationshipAndConcurrentStaleVersion() {
        FareConfiguration current = FareConfiguration.builder().id(1L).regularFare(new BigDecimal("75.00"))
                .discountedFare(new BigDecimal("60.00")).version(4L).build();
        FareConfigurationRepository repository = mock(FareConfigurationRepository.class);
        when(repository.findActiveForUpdate()).thenReturn(Optional.of(current));
        var service = service(repository);

        var relationship = assertThrows(com.premier.exception.ClientException.class,
                () -> service.update(new BigDecimal("50.00"), new BigDecimal("50.00"), 4L, null));
        assertEquals(HttpStatus.BAD_REQUEST, relationship.getStatus());
        var stale = assertThrows(com.premier.exception.ClientException.class,
                () -> service.update(new BigDecimal("80.00"), new BigDecimal("64.00"), 3L, null));
        assertEquals(HttpStatus.CONFLICT, stale.getStatus());
        verify(repository, never()).saveAndFlush(any());
    }
}
