package com.payflow.ledger.domain;

/**
 * Customer accounts are money the bank owes, so a DEBIT lowers an account's balance and a
 * CREDIT raises it. Balance = credits - debits.
 */
public enum Direction {
    DEBIT,
    CREDIT
}
