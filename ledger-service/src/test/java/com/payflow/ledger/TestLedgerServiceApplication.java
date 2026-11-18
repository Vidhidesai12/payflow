package com.payflow.ledger;

import org.springframework.boot.SpringApplication;

/** Runs the ledger locally with throwaway containers instead of docker-compose. */
public class TestLedgerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.from(LedgerServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
