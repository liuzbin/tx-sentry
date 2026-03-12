package com.web3.txsentry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TxSentryApplication {

	public static void main(String[] args) {
		SpringApplication.run(TxSentryApplication.class, args);
	}

}
