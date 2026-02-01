package com.opayque.api.wallet.repository;

import com.opayque.api.wallet.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface LedgerRepository extends JpaRepository<LedgerEntry, UUID> {

    // FIX: Replaced string literal 'CREDIT' with Fully Qualified Enum Name
    // This prevents the "ValidationFailed: semantic error" crash on startup
    @Query("""
        SELECT COALESCE(SUM(
            CASE 
                WHEN l.transactionType = com.opayque.api.wallet.entity.TransactionType.CREDIT 
                THEN l.amount 
                ELSE -l.amount 
            END
        ), 0) 
        FROM LedgerEntry l 
        WHERE l.account.id = :accountId
    """)
    java.math.BigDecimal getBalance(UUID accountId);
}