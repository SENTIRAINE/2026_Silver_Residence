package org.example.xqy1._026_silver_residence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = "housing.search.snapshot-prewarm-enabled=false")
class ApplicationTests {
	@Value("${agent.runtime.max-run-duration-ms}")
	private long maxRunDurationMs;

	@Value("${agent.runtime.event-retention-ms}")
	private long eventRetentionMs;

	@Value("${agent.runtime.heartbeat-interval-ms}")
	private long heartbeatIntervalMs;

	@Value("${spring.mvc.async.request-timeout}")
	private long asyncTimeoutMs;

	@Test
	void contextLoads() {
		assertEquals(180_000L, maxRunDurationMs);
		assertEquals(86_400_000L, eventRetentionMs);
		assertEquals(15_000L, heartbeatIntervalMs);
		assertEquals(210_000L, asyncTimeoutMs);
	}

}
