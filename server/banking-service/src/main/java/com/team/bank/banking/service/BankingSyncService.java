package com.team.bank.banking.service;

import com.team.bank.banking.client.EnableBankingClient;
import com.team.bank.banking.model.Account;
import com.team.bank.banking.model.AccountRepository;
import com.team.bank.banking.model.BankingConnection;
import com.team.bank.banking.model.BankingConnectionRepository;
import com.team.bank.banking.model.Transaction;
import com.team.bank.banking.model.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BankingSyncService {

    private static final Logger log = LoggerFactory.getLogger(BankingSyncService.class);

    private final EnableBankingClient ebClient;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final BankingConnectionRepository bankingConnectionRepository;

    public BankingSyncService(EnableBankingClient ebClient,
                              AccountRepository accountRepository,
                              TransactionRepository transactionRepository,
                              BankingConnectionRepository bankingConnectionRepository) {
        this.ebClient = ebClient;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.bankingConnectionRepository = bankingConnectionRepository;
    }

    @SuppressWarnings("unchecked")
    public void syncAccount(BankingConnection connection) {
        String externalUid = connection.getExternalAccountUid();
        // Without an external account UID every Enable Banking call would hit /accounts/null/...
        // Skip the sync (and don't touch the connection) so it isn't falsely marked as synced.
        if (externalUid == null || externalUid.isBlank()) {
            log.warn("Skipping sync for connection {} (bank: {}): no external account UID",
                connection.getId(), connection.getBankName());
            return;
        }

        // Sync balances
        Map<String, Object> balanceResponse = ebClient.getBalances(externalUid);
        if (balanceResponse != null) {
            List<Map<String, Object>> balances = (List<Map<String, Object>>) balanceResponse.get("balances");
            if (balances != null && !balances.isEmpty()) {
                Map<String, Object> firstBalance = balances.get(0);
                Map<String, Object> balanceAmount = (Map<String, Object>) firstBalance.get("balanceAmount");
                if (balanceAmount != null) {
                    BigDecimal amount = new BigDecimal(balanceAmount.get("amount").toString());
                    Account account = accountRepository.findById(connection.getAccountId()).orElse(null);
                    if (account != null) {
                        account.setBalance(amount);
                        account.setUpdatedAt(LocalDateTime.now());
                        accountRepository.save(account);
                    }
                }
            }
        }

        // Sync transactions
        Map<String, Object> txResponse = ebClient.getTransactions(externalUid);
        if (txResponse != null) {
            List<Map<String, Object>> transactions = (List<Map<String, Object>>) txResponse.get("transactions");
            if (transactions != null) {
                for (Map<String, Object> tx : transactions) {
                    // transactionAmount is a nested object ({amount, currency}) just like
                    // balanceAmount above, so read the inner "amount" rather than stringifying
                    // the whole map (which would throw NumberFormatException).
                    BigDecimal txAmount = parseAmount(tx.get("transactionAmount"));
                    if (txAmount == null) {
                        continue;
                    }
                    String creditDebitIndicator = (String) tx.get("creditDebitIndicator");
                    String direction = "CRDT".equalsIgnoreCase(creditDebitIndicator)
                        || "CREDIT".equalsIgnoreCase(creditDebitIndicator)
                        ? "CREDIT" : "DEBIT";
                    String category = tx.get("remittanceInformationUnstructured") != null
                        ? tx.get("remittanceInformationUnstructured").toString()
                        : "Uncategorized";

                    // Dedup check
                    List<Transaction> existing = transactionRepository
                        .findByAccountIdAndCategoryAndAmountAndDirection(
                            connection.getAccountId(), category, txAmount, direction);
                    if (!existing.isEmpty()) {
                        continue;
                    }

                    Transaction newTx = new Transaction();
                    newTx.setId(UUID.randomUUID());
                    newTx.setAccountId(connection.getAccountId());
                    newTx.setCategory(category);
                    newTx.setAmount(txAmount);
                    newTx.setDirection(direction);
                    newTx.setCreatedAt(LocalDateTime.now());
                    transactionRepository.save(newTx);
                }
            }
        }

        // Update connection timestamp
        connection.setUpdatedAt(LocalDateTime.now());
        bankingConnectionRepository.save(connection);

        log.info("Synced account for connection {} (bank: {})", connection.getId(), connection.getBankName());
    }

    /**
     * Extracts a monetary value from an Enable Banking amount field, which may be either a
     * nested object ({@code {amount, currency}}) or a bare scalar. Returns null when no usable
     * amount is present so callers can skip the record instead of failing the whole sync.
     */
    @SuppressWarnings("unchecked")
    private BigDecimal parseAmount(Object amountField) {
        Object raw = amountField instanceof Map
            ? ((Map<String, Object>) amountField).get("amount")
            : amountField;
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw.toString());
        } catch (NumberFormatException e) {
            log.warn("Could not parse transaction amount '{}'", raw);
            return null;
        }
    }
}
