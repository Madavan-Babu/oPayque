package com.opayque.api.admin.dto;

import com.opayque.api.admin.controller.AdminWalletController;
import com.opayque.api.admin.service.AdminWalletService;
import com.opayque.api.identity.entity.User;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.AccountStatus;
import com.opayque.api.wallet.repository.AccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p>This test class validates the mapping behavior of the {@link AdminAccountResponse}
 * DTO when converting from the {@link Account} entity. It ensures that:</p>
 *
 * <ul>
 *   <li>All core fields (identifier, IBAN, currency, status) are transferred accurately.</li>
 *   <li>The {@code ownerEmail} property is populated only when a {@link User} is associated
 *       with the {@link Account}; otherwise it remains {@code null}.</li>
 *   <li>The record-style constructor of {@link AdminAccountResponse} correctly stores
 *       the supplied values.</li>
 * </ul>
 *
 * <p>The tests exercise both branches of the ternary logic used in
 * {@code AdminAccountResponse.fromEntity(account)} to guarantee robust handling of
 * nullable relationships, which is essential for maintaining data integrity in
 * multi‑currency account management scenarios.</p>
 *
 * @author Madavan Babu
 * @since 2026
 *
 * @see AdminAccountResponse
 * @see Account
 * @see User
 */
@Tag("unit")
class AdminAccountResponseTest {

    /**
     * Verifies that {@link AdminAccountResponse#fromEntity(Account)} correctly maps all
     * relevant fields from an {@link Account} entity when the associated {@link User}
     * is present.
     *
     * <p>The test creates a fully populated {@link Account} with an owned {@link User}
     * and asserts that the resulting {@link AdminAccountResponse} contains the expected
     * {@code id}, {@code iban}, {@code currency}, {@code status}, and {@code ownerEmail}
     * values. This ensures the mapping logic faithfully transfers both account data and
     * the owner's email address, which is essential for administrative views that
     * display ownership information.</p>
     *
     * @see AdminAccountResponse
     * @see Account
     * @see User
     */
    @Test
    @DisplayName("Mapper: Should map full entity including ownerEmail when User is present")
    void fromEntity_ShouldMapCompleteData_WhenUserExists() {
        // Arrange
        UUID accountId = UUID.randomUUID();
        String testIban = "CH930000123456789";
        String testEmail = "client@opayque.ch";

        User owner = User.builder()
                .email(testEmail)
                .build();

        Account account = Account.builder()
                .id(accountId)
                .iban(testIban)
                .currencyCode("CHF")
                .status(AccountStatus.ACTIVE)
                .user(owner)
                .build();

        // Act
        AdminAccountResponse response = AdminAccountResponse.fromEntity(account);

        // Assert
        assertThat(response.id()).isEqualTo(accountId);
        assertThat(response.iban()).isEqualTo(testIban);
        assertThat(response.currency()).isEqualTo("CHF");
        assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(response.ownerEmail()).isEqualTo(testEmail); // Branch 1: User exists
    }

    /**
     * Verifies that {@link AdminAccountResponse#fromEntity(Account)} correctly handles
     * an {@link Account} whose {@link User} reference is {@code null}.
     *
     * <p>The test builds an {@link Account} with a populated {@code id}, {@code iban},
     * {@code currencyCode} and {@code status}, but deliberately sets the {@code user}
     * association to {@code null} to trigger the ternary branch inside the mapper.
     * It asserts that the resulting {@link AdminAccountResponse} contains a {@code null}
     * {@code ownerEmail} while preserving the other mapped values, ensuring that
     * the mapper does not throw a {@link NullPointerException} for orphaned accounts.
     *
     * <p>This behavior is essential for administrative APIs that present account
     * information even when the owning user has been removed or is otherwise absent.
     *
     * @see AdminAccountResponse
     * @see Account
     * @see User
     */
    @Test
    @DisplayName("Mapper: Should handle null User gracefully and return null ownerEmail")
    void fromEntity_ShouldHandleNullUser_ToCoverTernaryBranch() {
        // Arrange
        Account orphanedAccount = Account.builder()
                .id(UUID.randomUUID())
                .iban("CH939999999999999")
                .currencyCode("EUR")
                .status(AccountStatus.CLOSED)
                .user(null) // Branch 2: User is null
                .build();

        // Act
        AdminAccountResponse response = AdminAccountResponse.fromEntity(orphanedAccount);

        // Assert
        assertThat(response.ownerEmail()).isNull(); // Verification of the ternary logic
        assertThat(response.status()).isEqualTo(AccountStatus.CLOSED);
    }

    /**
     * Verifies that the {@link AdminAccountResponse} record correctly stores
     * and returns the values supplied to its canonical constructor.
     *
     * <p>The test creates a {@link UUID} identifier and constructs an
     * {@link AdminAccountResponse} with representative data (IBAN, currency,
     * {@link AccountStatus}, and owner e‑mail). It then asserts that the accessor
     * methods {@code id()}, {@code currency()}, and {@code status()} return the
     * exact values provided, ensuring the immutable DTO reliably preserves its
     * field integrity for administrative API responses.</p>
     *
     * @see AdminWalletController
     * @see AdminWalletService
     * @see AccountRepository
     */
    @Test
    @DisplayName("DTO: Record should maintain field integrity")
    void record_ShouldStoreValuesCorrectly() {
        // Simple verification that the record constructor works as expected
        UUID id = UUID.randomUUID();
        AdminAccountResponse response = new AdminAccountResponse(
                id,
                "IBAN123",
                "GBP",
                AccountStatus.FROZEN,
                "admin@opayque.com"
        );

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.currency()).isEqualTo("GBP");
        assertThat(response.status()).isEqualTo(AccountStatus.FROZEN);
    }
}