package com.premier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.premier.staffqueue.response.BusQueueDashboardResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

@SpringBootTest
@ActiveProfiles("test")
class PremierApplicationTests {

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void contextLoads() {
	}

	@Test
	void serializesLocalDateTimeWithPhilippineOffset() throws Exception {
		String json = objectMapper.writeValueAsString(new BusQueueDashboardResponse(
				LocalDateTime.of(2026, 8, 12, 9, 30), List.of(), List.of()));

		Assertions.assertTrue(json.contains("2026-08-12T09:30:00+08:00"));
	}

}
