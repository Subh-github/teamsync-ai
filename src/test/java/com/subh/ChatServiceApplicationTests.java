package com.subh;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Requires live Redis + PostgreSQL — enable with Testcontainers in integration test phase")
class ChatServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}

