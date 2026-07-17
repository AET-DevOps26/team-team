package com.team.bank.banking.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Assigns a spending category to a synced transaction. Resolution order: merchant keyword rules
 * over the counterparty + remittance text, then the card network's merchant category code, then the
 * ISO bank transaction code family; "Other" when nothing matches. Category labels match
 * scripts/demo-seed/generate.py so demo and live data read the same.
 */
final class TransactionCategorizer {

  private record Rule(String category, List<String> keywords) {}

  private static final List<Rule> RULES =
      List.of(
          new Rule(
              "Groceries",
              List.of(
                  "rewe",
                  "aldi",
                  "lidl",
                  "edeka",
                  "netto",
                  "kaufland",
                  "penny",
                  "denn",
                  "biomarkt",
                  "tegut",
                  "combi",
                  "grocer",
                  "supermarkt",
                  "supermarket")),
          new Rule(
              "Bakery",
              List.of(
                  "baeckerei",
                  "bäckerei",
                  "backhaus",
                  "brot",
                  "rischart",
                  "landbaeck",
                  "ihle",
                  "riedmair",
                  "wimme")),
          new Rule(
              "Drugstore & Health",
              List.of("dm-drog", "dm drogerie", "rossmann", "apotheke", "drogerie", "budni")),
          new Rule(
              "Transport",
              List.of(
                  "voi ",
                  "mvg",
                  "bvg",
                  "bahn",
                  "flix",
                  "sixt",
                  "uber",
                  "freenow",
                  "tier ",
                  "lime",
                  "bolt",
                  "tankstelle",
                  "shell",
                  "aral",
                  "esso")),
          new Rule(
              "Dining & Cafes",
              List.of(
                  "restaurant",
                  "gaststaett",
                  "gast", // Gasthaus/Gaststätte, also when the bank truncates the name
                  "cafe",
                  "café",
                  "coffee",
                  "pizz",
                  "sushi",
                  "kebab",
                  "imbiss",
                  "mcdonald",
                  "burger",
                  "wirtshaus",
                  "bistro",
                  "uncle chen",
                  "kistenpfennig",
                  "ariana",
                  "ls plex",
                  "rocketboys",
                  "shandiz")),
          new Rule(
              "Subscriptions",
              List.of(
                  "apple.com",
                  "claude.ai",
                  "netflix",
                  "spotify",
                  "disney",
                  "youtube",
                  "adobe",
                  "audible",
                  "dropbox",
                  "microsoft",
                  "openai")),
          new Rule(
              "Shopping",
              List.of(
                  "amazon",
                  "amzn",
                  "ikea",
                  "zalando",
                  "mediamarkt",
                  "saturn",
                  "galeria",
                  "tk maxx",
                  "otto",
                  "zara",
                  "decathlon",
                  "euroshop",
                  "blume")),
          // Card-funded money movement to other wallets: not merchant spending.
          new Rule("Transfers", List.of("revolut")));

  private static final List<String> REFUND_HINTS =
      List.of("paypal", "amazon", "amzn", "refund", "erstattung");

  private static final Map<String, String> MCC_CATEGORIES =
      Map.ofEntries(
          Map.entry("5411", "Groceries"),
          Map.entry("5422", "Groceries"),
          Map.entry("5451", "Groceries"),
          Map.entry("5499", "Groceries"),
          Map.entry("5462", "Bakery"),
          Map.entry("5122", "Drugstore & Health"),
          Map.entry("5912", "Drugstore & Health"),
          Map.entry("4111", "Transport"),
          Map.entry("4121", "Transport"),
          Map.entry("4131", "Transport"),
          Map.entry("5541", "Transport"),
          Map.entry("5542", "Transport"),
          Map.entry("7523", "Transport"),
          Map.entry("5812", "Dining & Cafes"),
          Map.entry("5813", "Dining & Cafes"),
          Map.entry("5814", "Dining & Cafes"),
          Map.entry("4899", "Subscriptions"),
          Map.entry("5968", "Subscriptions"),
          Map.entry("5311", "Shopping"),
          Map.entry("5651", "Shopping"),
          Map.entry("5691", "Shopping"),
          Map.entry("5732", "Shopping"),
          Map.entry("5942", "Shopping"),
          Map.entry("5945", "Shopping"),
          Map.entry("5999", "Shopping"));

  private TransactionCategorizer() {}

  static String categorize(
      String direction,
      String counterparty,
      String remittance,
      String merchantCategoryCode,
      String bankTransactionCode) {
    String hay =
        ((counterparty == null ? "" : counterparty) + " " + (remittance == null ? "" : remittance))
            .toLowerCase(Locale.ROOT);
    if ("CREDIT".equals(direction)) {
      return REFUND_HINTS.stream().anyMatch(hay::contains) ? "Refund" : "Income";
    }
    for (Rule rule : RULES) {
      if (rule.keywords().stream().anyMatch(hay::contains)) {
        return rule.category();
      }
    }
    if (merchantCategoryCode != null) {
      String byMcc = MCC_CATEGORIES.get(merchantCategoryCode);
      if (byMcc != null) {
        return byMcc;
      }
    }
    // ICDT = outgoing SEPA credit transfer: money moved, not merchant spending.
    if ("ICDT".equals(bankTransactionCode)) {
      return "Transfers";
    }
    return "Other";
  }
}
