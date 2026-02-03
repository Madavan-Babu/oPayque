package com.opayque.api.wallet.entity;

import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/// **Unit Testing Suite for the LedgerEntry Entity — Identity and Proxy Integrity Audit**.
///
/// This suite validates the high-precision `equals` and `hashCode` implementations required for
/// `JPA` entities, specifically ensuring compatibility with **Hibernate Lazy Loading** and
/// polymorphic proxies. It serves as a guardrail against common persistence bugs where
/// entity identity might be lost during object-relational mapping (ORM) transitions.
///
/// **Architectural Objectives:**
/// - **Identity Stability:** Ensures that identity is strictly bound to the Primary Key (UUID).
/// - **Proxy Transparency:** Verifies that equality logic remains valid even when entities are wrapped
///   in [HibernateProxy] instances.
/// - **Effective Java Compliance:** Adheres to the contracts of reflexivity, symmetry, and consistency.
class LedgerEntryTest{
    /// **Internal Test Utility: Manual Hibernate Proxy Stub**.
    ///
    /// Standard Mockito mocks struggle with final methods and complex proxy context checks.
    /// This static inner class provides a predictable, manual implementation of the
    /// [HibernateProxy] interface to trigger specific conditional branches in the
    /// [LedgerEntry#equals] logic.
    static class StubHibernateProxy extends LedgerEntry implements HibernateProxy {
        private final LazyInitializer initializer;

        StubHibernateProxy(LazyInitializer initializer) {
            this.initializer = initializer;
        }

        @Override
        public LazyInitializer getHibernateLazyInitializer() {
            return initializer;
        }

        @Override
        public Object writeReplace() {
            return null;
        }
    }

    /// Validates the reflexive property of the equality contract: an object must equal itself.
    @Test
    @DisplayName("Unit: Equals should be reflexive (Same Instance)")
    void equals_Reflexive() {
        LedgerEntry entry = LedgerEntry.builder().id(UUID.randomUUID()).build();
        assertTrue(entry.equals(entry));
    }

    /// Verifies that any non-null reference compared to null must return false.
    @Test
    @DisplayName("Unit: Equals should handle nulls")
    void equals_Null() {
        LedgerEntry entry = LedgerEntry.builder().id(UUID.randomUUID()).build();
        assertFalse(entry.equals(null));
    }

    /// Ensures that comparisons between [LedgerEntry] and unrelated classes return false.
    @Test
    @DisplayName("Unit: Equals should handle different classes")
    void equals_DifferentClass() {
        LedgerEntry entry = LedgerEntry.builder().id(UUID.randomUUID()).build();
        Object other = new Object();
        assertFalse(entry.equals(other));
    }

    /// **Core Business Logic Audit: Identity Verification**.
    ///
    /// Confirms that [LedgerEntry] equality is determined solely by the UUID primary key.
    /// It also verifies that unpersisted entities (Null IDs) are never considered equal
    /// to preserve the "Single Source of Truth" mandate.
    @Test
    @DisplayName("Unit: Equals should verify ID equality")
    void equals_IdCheck() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        LedgerEntry entry1 = LedgerEntry.builder().id(id1).build();
        LedgerEntry entry2 = LedgerEntry.builder().id(id1).build();
        LedgerEntry entry3 = LedgerEntry.builder().id(id2).build();
        LedgerEntry entryNullId = LedgerEntry.builder().id(null).build();

        assertTrue(entry1.equals(entry2)); // Identical UUIDs
        assertFalse(entry1.equals(entry3)); // Differing UUIDs
        assertFalse(entryNullId.equals(entry1)); // Comparing Null ID to Persisted ID
        assertFalse(entryNullId.equals(LedgerEntry.builder().id(null).build())); // Two Null IDs (Transient)
    }

    /// Validates that the hash code remains consistent and based on the persistent class,
    /// preventing issues when entities are used in `HashMap` or `HashSet` collections.
    @Test
    @DisplayName("Unit: HashCode should be consistent")
    void testHashCode() {
        LedgerEntry entry = LedgerEntry.builder().id(UUID.randomUUID()).build();
        assertEquals(LedgerEntry.class.hashCode(), entry.hashCode());
    }

    /// **Advanced Resilience: Proxy Identity Handling**.
    ///
    /// Tests the scenario where the object invoking `equals` is a [HibernateProxy].
    /// This is common when accessing child ledger entries via a lazy-loaded relationship.
    @Test
    @DisplayName("Unit: Equals should handle when 'this' is a HibernateProxy")
    void equals_WhenThisIsProxy() {
        UUID id = UUID.randomUUID();
        LedgerEntry realEntry = LedgerEntry.builder().id(id).build();

        // 1. Set up the LazyInitializer stub to return the expected persistent class
        LazyInitializer lazy = mock(LazyInitializer.class);
        doReturn(LedgerEntry.class).when(lazy).getPersistentClass();

        // 2. Create the proxy stub with matching ID
        StubHibernateProxy proxy = new StubHibernateProxy(lazy);
        proxy.setId(id);

        // 3. Assert equality is preserved across the proxy boundary
        assertTrue(proxy.equals(realEntry));
    }

    /// Verifies that a proxy's hash code correctly delegates to the persistent class's hash code.
    @Test
    @DisplayName("Unit: HashCode should handle when 'this' is a HibernateProxy")
    void hashCode_WhenThisIsProxy() {
        LazyInitializer lazy = mock(LazyInitializer.class);
        doReturn(LedgerEntry.class).when(lazy).getPersistentClass();

        StubHibernateProxy proxy = new StubHibernateProxy(lazy);

        // The Result should be the hash of LedgerEntry.class, not the proxy's class
        assertEquals(LedgerEntry.class.hashCode(), proxy.hashCode());
    }

    /// Tests symmetry: if the first object equals the second (proxy),
    /// the second must equal the first.
    @Test
    @DisplayName("Unit: Equals should handle when 'other' is a HibernateProxy")
    void equals_WhenOtherIsProxy() {
        UUID id = UUID.randomUUID();
        LedgerEntry realEntry = LedgerEntry.builder().id(id).build();

        LazyInitializer lazy = mock(LazyInitializer.class);
        doReturn(LedgerEntry.class).when(lazy).getPersistentClass();

        StubHibernateProxy proxy = new StubHibernateProxy(lazy);
        proxy.setId(id);

        // Act & Assert symmetry
        assertTrue(realEntry.equals(proxy));
    }
}