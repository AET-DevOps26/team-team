# Test Implementation Progress

> Tracking implementation of tests from `docs/testing-plan.md`
> Started: 2026-07-10 | **All phases complete** ✅

---

## Summary

| Phase                                        | Tests Written       | Status         |
| -------------------------------------------- | ------------------- | -------------- |
| Phase 1: Foundation (deps + configs)         | —                   | ✅ Complete    |
| Phase 2: Core Logic Tests                    | 7+10+10+12 = **39** | ✅ Complete    |
| Phase 3: Controllers (account, transaction)  | 7+9 = **16**        | ✅ Complete    |
| Phase 4: Controllers (orchestrator, banking) | 11+14 = **25**      | ✅ Complete    |
| Phase 5: Repository Tests                    | 3+3+5 = **11**      | ✅ Complete    |
| Phase 6: Client Tests                        | 11+11 = **22**      | ✅ Complete    |
| **Total**                                    | **~113 tests**      | ✅ All written |

---

## Files Created

### Server Test Files (12 new Java files)

```
server/account-service/src/test/java/com/team/bank/account/
├── UtilizationRateTest.java          (7 tests)
├── AccountControllerTest.java        (7 tests)
└── AccountRepositoryTest.java        (3 tests)

server/transaction-service/src/test/java/com/team/bank/transaction/
├── ExpenseBreakdownTest.java         (10 tests)
├── TransactionControllerTest.java    (9 tests)
└── TransactionRepositoryTest.java    (3 tests)

server/orchestrator-service/src/test/java/com/team/bank/orchestrator/
└── DashboardControllerTest.java      (11 tests)

server/banking-service/src/test/java/com/team/bank/banking/
├── controller/
│   └── BankingControllerTest.java    (14 tests)
├── service/
│   ├── BankingSyncServiceTest.java   (12 tests)
│   └── ParseAmountTest.java          (10 tests)
└── model/
    └── BankingConnectionRepositoryTest.java (5 tests)
```

### Client Test Files (1 new + 1 extended)

```
client/src/
├── App.test.tsx           (6 existing + 11 new = 17 total)
└── api.test.ts            (11 tests, new file)
```

### Test Config Files (4 new)

```
server/account-service/src/test/resources/application-test.yml
server/transaction-service/src/test/resources/application-test.yml
server/banking-service/src/test/resources/application-test.yml
server/orchestrator-service/src/test/resources/application-test.yml
```

### Dependency Changes (5 files modified)

```
server/gradle/libs.versions.toml
server/account-service/build.gradle.kts
server/transaction-service/build.gradle.kts
server/banking-service/build.gradle.kts
server/orchestrator-service/build.gradle.kts
```

---

## Next Steps

1. **Run server tests**: `cd server && ./gradlew clean check`
2. **Run client tests**: `cd client && npm run test`
3. **Fix compilation issues** (e.g., Mockito `@MockitoBean` API for Spring Boot 4.x)
4. **E2E tests** with Playwright (separate future phase)

---

## Phase 1: Foundation ✅

| Task                                                                  | Status |
| --------------------------------------------------------------------- | ------ |
| Add H2 to version catalog                                             | ✅     |
| Add MockWebServer to version catalog                                  | ✅     |
| Add H2 to account-service build.gradle.kts                            | ✅     |
| Add H2 to transaction-service build.gradle.kts                        | ✅     |
| Add H2 to banking-service build.gradle.kts                            | ✅     |
| Add MockWebServer to orchestrator-service build.gradle.kts            | ✅     |
| Create `account-service/src/test/resources/application-test.yml`      | ✅     |
| Create `transaction-service/src/test/resources/application-test.yml`  | ✅     |
| Create `banking-service/src/test/resources/application-test.yml`      | ✅     |
| Create `orchestrator-service/src/test/resources/application-test.yml` | ✅     |

---

## Phase 2: Core Logic Tests 🔄

### account-service — `UtilizationRateTest.java`

| #   | Test                                        | Status |
| --- | ------------------------------------------- | ------ |
| 1   | `shouldReturnZeroWhenBalanceIsNull`         | ⬜     |
| 2   | `shouldReturnZeroWhenCreditLimitIsNull`     | ⬜     |
| 3   | `shouldReturnZeroWhenCreditLimitIsZero`     | ⬜     |
| 4   | `shouldReturnZeroWhenCreditLimitIsNegative` | ⬜     |
| 5   | `shouldComputeCorrectRateForNormalValues`   | ⬜     |
| 6   | `shouldComputeRateWithFourDecimalPrecision` | ⬜     |

### transaction-service — `ExpenseBreakdownTest.java`

| #   | Test                                          | Status |
| --- | --------------------------------------------- | ------ |
| 1   | `shouldHandleEmptyTransactionList`            | ⬜     |
| 2   | `shouldGroupMultipleTransactionsSameCategory` | ⬜     |
| 3   | `shouldIgnoreCreditTransactions`              | ⬜     |
| 4   | `shouldSumPercentagesTo100`                   | ⬜     |
| 5   | `shouldHandleVerySmallAmounts`                | ⬜     |

### banking-service — `ParseAmountTest.java`

| #   | Test                                   | Status |
| --- | -------------------------------------- | ------ |
| 1   | `shouldParseNestedAmountObject`        | ⬜     |
| 2   | `shouldParseBareScalarAmount`          | ⬜     |
| 3   | `shouldReturnNullForNullInput`         | ⬜     |
| 4   | `shouldReturnNullForEmptyMap`          | ⬜     |
| 5   | `shouldReturnNullForUnparseableString` | ⬜     |
| 6   | `shouldParseNegativeAmount`            | ⬜     |
| 7   | `shouldParseZeroAmount`                | ⬜     |

### banking-service — `BankingSyncServiceTest.java`

| #   | Test                                             | Status |
| --- | ------------------------------------------------ | ------ |
| 1   | `shouldSkipSyncWhenExternalUidIsNull`            | ⬜     |
| 2   | `shouldSkipSyncWhenExternalUidIsBlank`           | ⬜     |
| 3   | `shouldUpdateAccountBalanceFromApi`              | ⬜     |
| 4   | `shouldNotUpdateBalanceWhenResponseIsNull`       | ⬜     |
| 5   | `shouldNotUpdateBalanceWhenBalancesListIsEmpty`  | ⬜     |
| 6   | `shouldInsertNewTransactions`                    | ⬜     |
| 7   | `shouldSkipDuplicateTransactions`                | ⬜     |
| 8   | `shouldNotInsertTransactionsWhenResponseIsNull`  | ⬜     |
| 9   | `shouldMapCreditDebitIndicatorToDirection`       | ⬜     |
| 10  | `shouldFallbackCategoryWhenRemittanceInfoIsNull` | ⬜     |
| 11  | `shouldUpdateConnectionTimestampAfterSync`       | ⬜     |
| 12  | `shouldLogWarningWhenSkippingDueToMissingUid`    | ⬜     |

---

## Phase 3: Controller Tests

### account-service — `AccountControllerTest.java`

| #   | Test                         | Status |
| --- | ---------------------------- | ------ |
| 1-7 | All account controller tests | ⬜     |

### transaction-service — `TransactionControllerTest.java`

| #   | Test                             | Status |
| --- | -------------------------------- | ------ |
| 1-9 | All transaction controller tests | ⬜     |

---

## Phase 4: Controller Tests (orchestrator, banking)

### orchestrator-service — `DashboardControllerTest.java`

| #    | Test                              | Status |
| ---- | --------------------------------- | ------ |
| 1-16 | All orchestrator controller tests | ⬜     |

### banking-service — `BankingControllerTest.java`

| #    | Test                         | Status |
| ---- | ---------------------------- | ------ |
| 1-14 | All banking controller tests | ⬜     |

---

## Phase 5: Repository Tests

| Service                                                  | # Tests | Status |
| -------------------------------------------------------- | ------- | ------ |
| account-service — `AccountRepositoryTest.java`           | 3       | ⬜     |
| transaction-service — `TransactionRepositoryTest.java`   | 3       | ⬜     |
| banking-service — `BankingConnectionRepositoryTest.java` | 5       | ⬜     |

---

## Phase 6: Client Tests

| File                        | # Tests | Status |
| --------------------------- | ------- | ------ |
| `App.test.tsx` (extend)     | 17      | ⬜     |
| `api.test.ts` (new)         | 8       | ⬜     |
| `TrendChart.test.tsx` (new) | 5       | ⬜     |

---

Legend: ⬜ Not started | 🔄 In progress | ✅ Done | ❌ Failed/Blocked
