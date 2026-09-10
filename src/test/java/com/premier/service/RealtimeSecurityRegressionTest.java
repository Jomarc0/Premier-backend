package com.premier.service;

import com.premier.admin.model.*;
import com.premier.admin.repository.AdminRepository;
import com.premier.admin.security.AdminJwtUtil;
import com.premier.model.*;
import com.premier.repository.PassengerRepository;
import com.premier.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class RealtimeSecurityRegressionTest {
    @Autowired RealtimeAuthorization authorization;
    @Autowired PassengerRepository passengers;
    @Autowired AdminRepository admins;
    @Autowired JwtUtil jwt;
    @Autowired AdminJwtUtil adminJwt;

    private Message<byte[]> frame(StompCommand command, String session, String destination, String token) {
        var a = StompHeaderAccessor.create(command); a.setSessionId(session);
        if (destination != null) a.setDestination(destination);
        if (token != null) a.setNativeHeader("Authorization", "Bearer " + token);
        a.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], a.getMessageHeaders());
    }
    @Test void passengerCannotPublishGuessDestinationsOrReceiveAfterRevocation() {
        var p = passengers.saveAndFlush(Passenger.builder().cardNumber(UUID.randomUUID().toString())
                .status(PassengerStatus.ACTIVE).is2FaEnabled(true).build());
        String session=UUID.randomUUID().toString();
        assertThatThrownBy(() -> authorization.inbound(frame(StompCommand.CONNECT,session,null,jwt.generateTempToken(p.getId()))))
                .isInstanceOf(SecurityException.class);
        authorization.inbound(frame(StompCommand.CONNECT,session,null,jwt.generateFullToken(p.getId())));
        authorization.inbound(frame(StompCommand.SUBSCRIBE,session,"/user/queue/realtime",null));
        for (String path : new String[]{"/topic/admin/realtime", "/topic/staff/realtime", "/topic/unknown", "/user/2/queue/realtime", "/queue/realtime", "/user/queue/realtime/extra"})
            assertThatThrownBy(() -> authorization.inbound(frame(StompCommand.SUBSCRIBE,session,path,null))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> authorization.inbound(frame(StompCommand.SEND,session,"/topic/admin/realtime",null)))
                .isInstanceOf(SecurityException.class);
        var delivery=frame(StompCommand.MESSAGE,session,"/user/queue/realtime",null);
        assertThat(authorization.outbound(delivery)).isNotNull();
        p.setSessionVersion(p.getSessionVersion()+1); passengers.saveAndFlush(p);
        assertThat(authorization.outbound(delivery)).isNull();
    }
    @Test void adminRoleChangesAndDisabledAccountsInvalidateExistingConnection() {
        String id=UUID.randomUUID().toString();
        var admin=admins.saveAndFlush(Admin.builder().adminId(id.substring(0,12)).username(id).fullName("Realtime test")
                .password("unused-synthetic").role(AdminRole.SUPER_ADMIN).is2FaEnabled(true).build());
        String session=UUID.randomUUID().toString();
        authorization.inbound(frame(StompCommand.CONNECT,session,null,adminJwt.generateAdminToken(admin.getId(),"SUPER_ADMIN")));
        authorization.inbound(frame(StompCommand.SUBSCRIBE,session,"/topic/admin/realtime",null));
        admin.setRole(AdminRole.STAFF); admins.saveAndFlush(admin);
        assertThat(authorization.outbound(frame(StompCommand.MESSAGE,session,"/topic/admin/realtime",null))).isNull();
        admin.setActive(false); admins.saveAndFlush(admin);
        assertThatThrownBy(() -> authorization.inbound(frame(StompCommand.CONNECT,UUID.randomUUID().toString(),null,
                adminJwt.generateAdminToken(admin.getId(),"STAFF")))).isInstanceOf(SecurityException.class);
    }
}
