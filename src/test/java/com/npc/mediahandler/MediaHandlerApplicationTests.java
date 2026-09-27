package com.npc.mediahandler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// In-memory DB and a scratch log file: the test must never touch ./data or ./logs
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:contextTest",
		"logging.file.name=target/test-logs/mediahandler.log"
})
class MediaHandlerApplicationTests {

	@Test
	void contextLoads() {
	}

}
