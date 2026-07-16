import { describe, it, expect, vi, afterEach } from "vitest";

import type { DashboardPayload } from "./api";
import {
  fetchDashboard,
  fetchBanks,
  connectBank,
  handleBankCallback,
  sendChat,
} from "./api";

const MOCK_ACCOUNT_ID = "11111111-1111-1111-1111-111111111111";

afterEach(() => {
  vi.restoreAllMocks();
});

function mockDashboard(
  overrides: Partial<DashboardPayload> = {},
): DashboardPayload {
  return {
    account: {
      accountId: "1",
      customerName: "Test User",
      totalBalance: 1200,
      totalCreditLimit: 4000,
      utilizationRate: 0.3,
    },
    trend: [{ month: "Jan", balance: 1000 }],
    expenses: [{ category: "Food", percentage: 40 }],
    aiSummary: "All good.",
    connectionStatus: null,
    connections: [],
    transactions: [],
    monthlyFlow: null,
    spendByBank: [],
    ...overrides,
  };
}

describe("fetchDashboard", () => {
  it("should fetch dashboard and return typed payload", async () => {
    const payload = mockDashboard();
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve(payload),
    } as Response);

    const result = await fetchDashboard(MOCK_ACCOUNT_ID);

    expect(result.account.customerName).toBe("Test User");
    expect(result.trend).toHaveLength(1);
  });

  it("should throw an error on non-ok response", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce({
      ok: false,
      status: 500,
    } as Response);

    await expect(fetchDashboard(MOCK_ACCOUNT_ID)).rejects.toThrow(
      "Failed to load dashboard",
    );
  });

  it("should construct the correct URL with account ID", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve(mockDashboard()),
    } as Response);

    await fetchDashboard(MOCK_ACCOUNT_ID);

    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining(`/api/dashboard/${MOCK_ACCOUNT_ID}`),
    );
  });
});

describe("fetchBanks", () => {
  it("should fetch banks for a given country", async () => {
    const banks = [{ name: "Nordea", country: "FI" }];
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve(banks),
    } as Response);

    const result = await fetchBanks("FI");

    expect(result).toHaveLength(1);
    expect(result[0].name).toBe("Nordea");
    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining("country=FI"),
    );
  });

  it("should throw when fetch fails", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce({
      ok: false,
      status: 503,
    } as Response);

    await expect(fetchBanks("DE")).rejects.toThrow("Failed to load banks");
  });
});

describe("connectBank", () => {
  it("should post correct JSON body and return auth URL", async () => {
    const authResponse = { authUrl: "https://auth.bank.com/oauth" };
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve(authResponse),
    } as Response);

    const result = await connectBank("Nordea", "FI", MOCK_ACCOUNT_ID);

    expect(result.authUrl).toBe("https://auth.bank.com/oauth");
    const callArgs = fetchSpy.mock.calls[0] as [string, RequestInit];
    expect(callArgs[1]?.method).toBe("POST");
    const body = JSON.parse(callArgs[1]?.body as string);
    expect(body.bankName).toBe("Nordea");
    expect(body.country).toBe("FI");
    expect(body.accountId).toBe(MOCK_ACCOUNT_ID);
  });

  it("should throw when connect fails", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce({
      ok: false,
      status: 400,
    } as Response);

    await expect(connectBank("BadBank", "XX", MOCK_ACCOUNT_ID)).rejects.toThrow(
      "Failed to initiate connection",
    );
  });
});

describe("handleBankCallback", () => {
  it("should post code and state to callback endpoint", async () => {
    const statusResponse = {
      status: "ACTIVE",
      bankName: "Nordea",
      country: "FI",
    };
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve(statusResponse),
    } as Response);

    const result = await handleBankCallback("auth-code-xyz", "state-token-abc");

    expect(result.status).toBe("ACTIVE");
    const callArgs = fetchSpy.mock.calls[0] as [string, RequestInit];
    const body = JSON.parse(callArgs[1]?.body as string);
    expect(body.code).toBe("auth-code-xyz");
    expect(body.state).toBe("state-token-abc");
  });
});

describe("sendChat", () => {
  it("should send chat messages and return reply", async () => {
    const reply = { reply: "Hello!", reasoning: null };
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve(reply),
    } as Response);

    const messages = [{ role: "user" as const, content: "Hi" }];
    const result = await sendChat(messages);

    expect(result.reply).toBe("Hello!");
  });

  it("should include context when provided", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve({ reply: "OK", reasoning: null }),
    } as Response);

    const context = {
      account: {
        accountId: "1",
        customerName: "T",
        totalBalance: 100,
        totalCreditLimit: 500,
        utilizationRate: 0.2,
      },
      trend: [],
      expenses: [],
      connections: [{ status: "ACTIVE", bankName: "Nordea", country: "FI", accountName: null, balance: null, currency: null }],
      transactions: [],
      monthlyFlow: null,
      spendByBank: [],
    };

    await sendChat([{ role: "user", content: "balance?" }], context);

    const callArgs = fetchSpy.mock.calls[0];
    expect(callArgs).toHaveLength(2);
    const body = JSON.parse((callArgs[1]?.body as string) ?? "{}");
    expect(body.context).toBeDefined();
    expect(body.context.connections[0].status).toBe("ACTIVE");
  });

  it("should throw when chat fails", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce({
      ok: false,
      status: 500,
    } as Response);

    await expect(sendChat([{ role: "user", content: "hi" }])).rejects.toThrow(
      "Failed to send message",
    );
  });
});
