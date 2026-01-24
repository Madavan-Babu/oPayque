package com.opayque.api.identity.entity;


/**
 * Epic 1: Identity & Access Management - Access Control
 * * Defines the Role-Based Access Control (RBAC) levels supported by the oPayque API.
 * These roles determine the granular permissions allowed for various banking operations.
 */
public enum Role {
    /** Standard user role with access to personal multi-currency wallets and transaction history. */
    CUSTOMER,

    /** Administrative role with privileges for system monitoring and account freezing. */
    ADMIN
}