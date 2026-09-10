package com.premier.model;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name="rfid_uid_registrations") @Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class RfidUidRegistration {
    @Id @Column(length=32) private String uid;
    @Column(nullable=false,length=16) private String ownerType;
    @Column(nullable=false) private Long ownerId;
}
