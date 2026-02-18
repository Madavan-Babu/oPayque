package com.opayque.api.admin.dto;

import com.opayque.api.identity.entity.User;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.AccountStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class AdminAccountResponseTest {

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