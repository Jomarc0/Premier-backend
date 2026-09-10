package com.premier.device.service;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="device.nonce-cleanup.enabled",havingValue="true",matchIfMissing=true)
public class DeviceNonceCleanup {
    private final JdbcTemplate jdbc;
    @Scheduled(fixedDelay=60000,initialDelay=60000) @Transactional
    public void cleanup() {
        // Retain a day, far beyond the five-minute authentication timestamp window. Bound each batch.
        jdbc.update("delete from device_request_nonces where id in (select id from device_request_nonces where created_at < localtimestamp - interval '1 day' order by created_at limit 5000)");
    }
}
