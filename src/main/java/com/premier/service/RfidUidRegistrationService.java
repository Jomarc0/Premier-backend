package com.premier.service;
import com.premier.model.RfidUidRegistration;
import com.premier.repository.RfidUidRegistrationRepository;
import com.premier.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service @RequiredArgsConstructor
public class RfidUidRegistrationService {
    private final RfidUidRegistrationRepository registrations;
    @Transactional(propagation=Propagation.MANDATORY)
    public void claim(String uid,String type,Long owner) {
        if(uid==null || !uid.matches("(?:[A-F0-9]{8}|[A-F0-9]{14}|[A-F0-9]{20})"))
            throw new ClientException(HttpStatus.BAD_REQUEST,"INVALID_UID","A complete 4, 7, or 10 byte RFID UID is required.");
        var existing=registrations.findById(uid);
        if(existing.isPresent()) {
            if(!existing.get().getOwnerType().equals(type)||!existing.get().getOwnerId().equals(owner))
                throw new ClientException(HttpStatus.CONFLICT,"UID_RESERVED","This RFID UID is already assigned or retired.");
            return;
        }
        // One primary key spans passenger and cash cards; concurrent claims cannot both commit.
        registrations.saveAndFlush(new RfidUidRegistration(uid,type,owner));
    }
}
