package com.opayque.api.wallet.entity;

import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerEntryTest {

    // --- HELPER CLASS: THE "MANUAL" PROXY ---
    // Instead of forcing Mockito to fake a final method context (which is flaky),
    // we create a real subclass that implements the interface.
    // This guarantees 'this instanceof HibernateProxy' is TRUE.
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

    @Test
    @DisplayName("Unit: Equals should be reflexive (Same Instance)")
    void equals_Reflexive() {
        LedgerEntry entry = LedgerEntry.builder().id(UUID.randomUUID()).build();
        assertTrue(entry.equals(entry));
    }

    @Test
    @DisplayName("Unit: Equals should handle nulls")
    void equals_Null() {
        LedgerEntry entry = LedgerEntry.builder().id(UUID.randomUUID()).build();
        assertFalse(entry.equals(null));
    }

    @Test
    @DisplayName("Unit: Equals should handle different classes")
    void equals_DifferentClass() {
        LedgerEntry entry = LedgerEntry.builder().id(UUID.randomUUID()).build();
        Object other = new Object();
        assertFalse(entry.equals(other));
    }

    @Test
    @DisplayName("Unit: Equals should verify ID equality")
    void equals_IdCheck() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        LedgerEntry entry1 = LedgerEntry.builder().id(id1).build();
        LedgerEntry entry2 = LedgerEntry.builder().id(id1).build();
        LedgerEntry entry3 = LedgerEntry.builder().id(id2).build();
        LedgerEntry entryNullId = LedgerEntry.builder().id(null).build();

        assertTrue(entry1.equals(entry2)); // Same ID
        assertFalse(entry1.equals(entry3)); // Diff ID
        assertFalse(entryNullId.equals(entry1)); // Null ID
        assertFalse(entryNullId.equals(LedgerEntry.builder().id(null).build())); // Both Null
    }

    @Test
    @DisplayName("Unit: HashCode should be consistent")
    void testHashCode() {
        LedgerEntry entry = LedgerEntry.builder().id(UUID.randomUUID()).build();
        // LedgerEntry uses getClass().hashCode() for real entities
        assertEquals(LedgerEntry.class.hashCode(), entry.hashCode());
    }

    @Test
    @DisplayName("Unit: Equals should handle when 'this' is a HibernateProxy")
    void equals_WhenThisIsProxy() {
        UUID id = UUID.randomUUID();
        LedgerEntry realEntry = LedgerEntry.builder().id(id).build();

        // 1. Setup the LazyInitializer stub
        LazyInitializer lazy = mock(LazyInitializer.class);
        doReturn(LedgerEntry.class).when(lazy).getPersistentClass();

        // 2. Create our Manual Proxy
        StubHibernateProxy proxy = new StubHibernateProxy(lazy);
        // We use the standard Setter (from @Setter on LedgerEntry) to populate the ID
        proxy.setId(id);

        // 3. Act & Assert
        // Logic Triggers:
        // - this instanceof HibernateProxy -> TRUE (StubHibernateProxy implements it)
        // - ((HibernateProxy) this).getHibernateLazyInitializer() -> returns our mock lazy
        // - lazy.getPersistentClass() -> returns LedgerEntry.class
        // - IDs match
        assertTrue(proxy.equals(realEntry));
    }

    @Test
    @DisplayName("Unit: HashCode should handle when 'this' is a HibernateProxy")
    void hashCode_WhenThisIsProxy() {
        // 1. Setup Stub
        LazyInitializer lazy = mock(LazyInitializer.class);
        doReturn(LedgerEntry.class).when(lazy).getPersistentClass();

        // 2. Create Proxy
        StubHibernateProxy proxy = new StubHibernateProxy(lazy);

        // 3. Act
        int hash = proxy.hashCode();

        // 4. Assert
        // Should use the 'persistentClass' hash code, not the proxy class hash code
        assertEquals(LedgerEntry.class.hashCode(), hash);
    }

    @Test
    @DisplayName("Unit: Equals should handle when 'other' is a HibernateProxy")
    void equals_WhenOtherIsProxy() {
        UUID id = UUID.randomUUID();
        LedgerEntry realEntry = LedgerEntry.builder().id(id).build();

        // Setup the 'other' proxy
        LazyInitializer lazy = mock(LazyInitializer.class);
        doReturn(LedgerEntry.class).when(lazy).getPersistentClass();

        StubHibernateProxy proxy = new StubHibernateProxy(lazy);
        proxy.setId(id);

        // Act: realEntry.equals(proxy)
        assertTrue(realEntry.equals(proxy));
    }
}