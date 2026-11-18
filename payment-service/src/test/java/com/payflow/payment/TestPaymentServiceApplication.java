package com.payflow.payment;

import org.springframework.boot.SpringApplication;

/** Runs payment-service locally with throwaway PostgreSQL and Kafka containers. */
public class TestPaymentServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(PaymentServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
