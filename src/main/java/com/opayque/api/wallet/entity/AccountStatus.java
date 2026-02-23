package com.opayque.api.wallet.entity;

import java.util.Set;

public enum AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED;

    /**
     * State Machine: Defines which transitions are legally permitted. <br>
     * ACTIVE -> can go to FROZEN (Admin Kill-switch) or CLOSED (Soft delete). <br>
     * FROZEN -> can go back to ACTIVE (Admin lift) or CLOSED (Finality). <br>
     * CLOSED -> Terminal state. Cannot be changed (Compliance/Audit). <br>
     */
    public boolean canTransitionTo(AccountStatus nextStatus) {
        return switch (this) {
            case ACTIVE -> Set.of(FROZEN, CLOSED).contains(nextStatus);
            case FROZEN -> Set.of(ACTIVE, CLOSED).contains(nextStatus);
            case CLOSED -> false; // Terminal state: once closed, it stays closed.
        };
    }
}