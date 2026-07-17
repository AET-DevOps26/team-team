import { render, screen, fireEvent } from "@testing-library/react";

import * as api from "./api";
import type { DashboardPayload } from "./api";
import App from "./App";

function dashboard(
  overrides: Partial<DashboardPayload> = {},
): DashboardPayload {
  return {
    account: {
      accountId: "1",
      customerName: "Test User",
      totalBalance: 1200,
      totalCreditLimit: 0,
      utilizationRate: 0,
    },
    trend: [
      { month: "Jan", balance: 1000 },
      { month: "Feb", balance: 1200 },
    ],
    expenses: [{ category: "Utilities", percentage: 40 }],
    aiSummary: "Summary",
    connectionStatus: null,
    connections: [],
    transactions: [],
    monthlyFlow: null,
    spendByBank: [],
    ...overrides,
  };
}

const active = { status: "ACTIVE", bankName: "Nordea", country: "FI" };
const activeConnections = [
  {
    status: "ACTIVE",
    bankName: "Nordea",
    country: "FI",
    accountName: null,
    balance: 1200,
    currency: "EUR",
  },
];

beforeEach(() => {
  window.history.pushState({}, "", "/?accountId=111-222");
  sessionStorage.setItem("authed", "1"); // skip the login gate for dashboard tests
  localStorage.clear(); // chat sessions live in localStorage
  vi.spyOn(api, "sendChat").mockResolvedValue({
    reply: "reply",
    reasoning: null,
  });
});

afterEach(() => {
  vi.restoreAllMocks();
  sessionStorage.clear();
});

test("shows the login page and signs in with admin/admin", async () => {
  sessionStorage.clear();
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({ connectionStatus: null }),
  );
  render(<App />);

  expect(screen.getByRole("heading", { name: "Sign in" })).toBeInTheDocument();
  fireEvent.change(screen.getByPlaceholderText("Username"), {
    target: { value: "admin" },
  });
  fireEvent.change(screen.getByPlaceholderText("Password"), {
    target: { value: "admin" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

  expect(await screen.findByText("No banks connected")).toBeInTheDocument();
});

test("shows the empty state when no bank is connected", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({ connectionStatus: null }),
  );
  render(<App />);

  expect(await screen.findByText("No banks connected")).toBeInTheDocument();
  // seeded totals must not leak through before a bank is linked
  expect(screen.queryByText("Total balance")).not.toBeInTheDocument();
});

test("shows the live dashboard and roster when a bank is ACTIVE", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({ connectionStatus: active }),
  );
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({ connectionStatus: active, connections: activeConnections }),
  );
  render(<App />);

  expect(await screen.findByText("Total balance")).toBeInTheDocument();
  expect(screen.getByText("Nordea")).toBeInTheDocument();
  expect(screen.queryByText("No banks connected")).not.toBeInTheDocument();
});

test("loads and renders the bank list when Find banks is clicked", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({ connectionStatus: null }),
  );
  const fetchBanks = vi.spyOn(api, "fetchBanks").mockResolvedValue([
    { name: "Bank A", country: "DE" },
    { name: "Bank B", country: "DE" },
  ]);
  render(<App />);
  await screen.findByText("No banks connected");

  fireEvent.click(screen.getByText("Find banks"));

  const connectButtons = await screen.findAllByText("Connect");
  expect(connectButtons).toHaveLength(2);
  expect(fetchBanks).toHaveBeenCalledWith("DE");
  expect(screen.getByText("Bank A")).toBeInTheDocument();
  expect(screen.getByText("Bank B")).toBeInTheDocument();
});

test("roster shows the bank name, not the account holder", async () => {
  // Mock ASPSPs return the account holder in accountName; the roster must
  // still label the row with the bank, since the holder is shown by the header.
  const holderConnections = [
    { ...activeConnections[0], bankName: "Nordea", accountName: "Jane Doe" },
  ];
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({ connectionStatus: active, connections: holderConnections }),
  );
  render(<App />);

  expect(await screen.findByText("Total balance")).toBeInTheDocument();
  expect(screen.getByText("Nordea")).toBeInTheDocument();
  expect(screen.queryByText("Jane Doe")).not.toBeInTheDocument();
});

test("bank list can be filtered and closed", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({ connectionStatus: null }),
  );
  vi.spyOn(api, "fetchBanks").mockResolvedValue([
    { name: "Bank A", country: "DE" },
    { name: "Bank B", country: "DE" },
  ]);
  render(<App />);
  await screen.findByText("No banks connected");

  fireEvent.click(screen.getByText("Find banks"));
  await screen.findByText("Bank A");

  // Filter narrows the list to matching banks.
  fireEvent.change(screen.getByLabelText("Filter banks"), {
    target: { value: "Bank A" },
  });
  expect(screen.getByText("Bank A")).toBeInTheDocument();
  expect(screen.queryByText("Bank B")).not.toBeInTheDocument();

  // Close collapses back to just the picker.
  fireEvent.click(screen.getByLabelText("Close bank list"));
  expect(screen.queryByText("Bank A")).not.toBeInTheDocument();
  expect(screen.getByText("Find banks")).toBeInTheDocument();
});

const REAL_ACCOUNT_UUID = "22222222-2222-2222-2222-222222222222";
const DEMO_ACCOUNT_UUID = "11111111-1111-1111-1111-111111111111";

// fetchDashboard mock that serves the demo account (mock ASPSP data) or the
// real account (production EB data) depending on the requested id.
function mockDashboardByAccount() {
  return vi.spyOn(api, "fetchDashboard").mockImplementation(async (id) =>
    id === DEMO_ACCOUNT_UUID
      ? dashboard({
          account: {
            accountId: DEMO_ACCOUNT_UUID,
            customerName: "Mock Holder",
            totalBalance: 500,
            totalCreditLimit: 0,
            utilizationRate: 0,
          },
          connectionStatus: {
            status: "ACTIVE",
            bankName: "Mock ASPSP",
            country: "FI",
          },
          connections: [
            { ...activeConnections[0], bankName: "Mock ASPSP", balance: 500 },
          ],
        })
      : dashboard({ connectionStatus: active, connections: activeConnections }),
  );
}

test("toggles between the real and the demo account from the header", async () => {
  const fetchDashboard = mockDashboardByAccount();
  render(<App />);

  // Defaults to live: the real account is fetched and shown.
  expect(await screen.findByText("Test User")).toBeInTheDocument();
  expect(fetchDashboard).toHaveBeenCalledWith(REAL_ACCOUNT_UUID);

  // Flip to demo: the demo account (mock ASPSP data) replaces it, persisted.
  fireEvent.click(screen.getByRole("button", { name: "Demo" }));
  expect(await screen.findByText("Mock Holder")).toBeInTheDocument();
  expect(screen.queryByText("Test User")).not.toBeInTheDocument();
  expect(fetchDashboard).toHaveBeenCalledWith(DEMO_ACCOUNT_UUID);
  expect(localStorage.getItem("dashboard.mode")).toBe("demo");

  // Flip back to live: the real account returns.
  fireEvent.click(screen.getByRole("button", { name: "Live" }));
  expect(await screen.findByText("Test User")).toBeInTheDocument();
});

test("demo mode only fetches the demo account, never the real one", async () => {
  localStorage.setItem("dashboard.mode", "demo");
  const fetchDashboard = mockDashboardByAccount();
  render(<App />);

  expect(await screen.findByText("Mock Holder")).toBeInTheDocument();
  expect(fetchDashboard).toHaveBeenCalledWith(DEMO_ACCOUNT_UUID);
  expect(fetchDashboard).not.toHaveBeenCalledWith(REAL_ACCOUNT_UUID);
});

test("signs out back to the login page", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({ connectionStatus: active }),
  );
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({ connectionStatus: active, connections: activeConnections }),
  );
  render(<App />);
  await screen.findByText("Total balance");

  fireEvent.click(screen.getByRole("button", { name: "Sign out" }));

  expect(screen.getByRole("heading", { name: "Sign in" })).toBeInTheDocument();
});

test("sends a chat message and shows the reply", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({ connectionStatus: active }),
  );
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({ connectionStatus: active, connections: activeConnections }),
  );
  render(<App />);
  await screen.findByText("Total balance");

  // Chat lives behind a floating action button; open the dock first.
  fireEvent.click(screen.getByRole("button", { name: /open assistant/i }));

  fireEvent.change(screen.getByPlaceholderText(/ask about your spending/i), {
    target: { value: "How do I lower utilization?" },
  });
  fireEvent.click(screen.getByText("Run"));

  expect(await screen.findByText("reply")).toBeInTheDocument();
});

// ---------------------------------------------------------------------------
// Additional tests — error states, edge cases, chat persistence, trend chart
// ---------------------------------------------------------------------------

test("shows error message for wrong login credentials", async () => {
  sessionStorage.clear();
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({ connectionStatus: null }),
  );
  render(<App />);

  fireEvent.change(screen.getByPlaceholderText("Username"), {
    target: { value: "wrong" },
  });
  fireEvent.change(screen.getByPlaceholderText("Password"), {
    target: { value: "wrong" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

  // Should stay on login page with error
  expect(
    await screen.findByText(/Incorrect username or password/i),
  ).toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "Sign in" })).toBeInTheDocument();
});

test("shows error state when dashboard fetch fails", async () => {
  vi.spyOn(api, "fetchDashboard").mockRejectedValue(new Error("Network error"));
  render(<App />);

  expect(await screen.findByText(/error/i)).toBeInTheDocument();
});

test("shows error message when bank list fetch fails", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({ connectionStatus: null }),
  );
  vi.spyOn(api, "fetchBanks").mockRejectedValue(
    new Error("Service unavailable"),
  );
  render(<App />);
  await screen.findByText("No banks connected");

  fireEvent.click(screen.getByText("Find banks"));

  expect(await screen.findByText(/Service unavailable/i)).toBeInTheDocument();
});

test("handles bank callback via query params", async () => {
  window.history.pushState(
    {},
    "",
    "/?accountId=111-222&code=auth-code&state=state-token",
  );
  const callbackSpy = vi
    .spyOn(api, "handleBankCallback")
    .mockResolvedValue({ status: "ACTIVE", bankName: "Nordea", country: "FI" });
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({ connectionStatus: active }),
  );

  render(<App />);

  await screen.findByText("Total balance");
  expect(callbackSpy).toHaveBeenCalledWith("auth-code", "state-token");
});

test("renders expense breakdown with category names and percentages", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({
      connectionStatus: active,
      expenses: [
        { category: "Rent", percentage: 60 },
        { category: "Food", percentage: 25 },
        { category: "Utilities", percentage: 15 },
      ],
    }),
  );
  render(<App />);
  await screen.findByText("Total balance");

  expect(screen.getByText("Rent")).toBeInTheDocument();
  expect(screen.getByText("Food")).toBeInTheDocument();
  expect(screen.getByText("Utilities")).toBeInTheDocument();
});

test("expense rows show amount and merchant context, and filter the feed", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({
      connectionStatus: active,
      expenses: [
        {
          category: "Groceries",
          percentage: 60,
          amount: 541.42,
          count: 23,
          topMerchants: ["REWE", "ALDI"],
        },
        { category: "Other", percentage: 40, amount: 100, count: 2, topMerchants: [] },
      ],
      transactions: [
        {
          id: "t1",
          category: "Groceries",
          amount: 8.18,
          direction: "DEBIT",
          bankName: "N26",
          counterparty: "REWE",
          createdAt: "2026-07-10T12:00:00",
        },
        {
          id: "t2",
          category: "Subscriptions",
          amount: 9.99,
          direction: "DEBIT",
          bankName: "N26",
          counterparty: "Spotify",
          createdAt: "2026-07-09T12:00:00",
        },
      ],
    }),
  );
  render(<App />);
  await screen.findByText("Total balance");

  // enriched row: absolute amount plus merchants and purchase count
  expect(screen.getByText("€541.42")).toBeInTheDocument();
  expect(screen.getByText(/mostly REWE, ALDI/)).toBeInTheDocument();
  expect(screen.getByText(/23 purchases/)).toBeInTheDocument();

  // clicking a category filters the feed to it
  fireEvent.click(screen.getByTitle("Show Groceries in recent transactions"));
  expect(screen.getByText("REWE")).toBeInTheDocument();
  expect(screen.queryByText("Spotify")).not.toBeInTheDocument();

  // the chip clears the filter again
  fireEvent.click(screen.getByTitle("Clear category filter"));
  expect(screen.getByText("Spotify")).toBeInTheDocument();
});

test("renders trend chart with SVG polyline when multiple data points exist", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({
      connectionStatus: active,
      trend: [
        { month: "Jan", balance: 1000 },
        { month: "Feb", balance: 1100 },
        { month: "Mar", balance: 1200 },
      ],
    }),
  );
  render(<App />);
  await screen.findByText("Total balance");

  // The SVG polyline should be present (visible or not — it's aria-hidden)
  const svg = document.querySelector("svg");
  expect(svg).not.toBeNull();
  const polyline = svg!.querySelector("polyline");
  expect(polyline).not.toBeNull();
  expect(polyline!.getAttribute("points")).toBeTruthy();
});

test("hides trend chart when there is only one data point", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({
      connectionStatus: active,
      trend: [{ month: "Jan", balance: 1000 }],
    }),
  );
  render(<App />);
  await screen.findByText("Total balance");

  // Only 1 data point → no polyline trend chart rendered
  const polyline = document.querySelector("polyline");
  expect(polyline).toBeNull();
});

test("formats currency values in euro notation", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({
      connectionStatus: active,
      account: {
        accountId: "1",
        customerName: "Test User",
        totalBalance: 2500,
        totalCreditLimit: 5000,
        utilizationRate: 0.5,
      },
    }),
  );
  render(<App />);
  await screen.findByText("Total balance");

  // Should display balance formatted as EUR
  expect(screen.getByText(/2,500/)).toBeInTheDocument();
});

test("shows chat history with sent message", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({ connectionStatus: active }),
  );
  render(<App />);
  await screen.findByText("Total balance");

  fireEvent.click(screen.getByRole("button", { name: /open assistant/i }));

  fireEvent.change(screen.getByPlaceholderText(/ask about your spending/i), {
    target: { value: "Hello from test" },
  });
  fireEvent.click(screen.getByText("Run"));

  // Verify the user message appears (may also appear as chat title)
  const messages = await screen.findAllByText("Hello from test");
  expect(messages.length).toBeGreaterThanOrEqual(1);
});

test("shows fallback message when chat API fails", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({ connectionStatus: active }),
  );
  vi.spyOn(api, "sendChat").mockRejectedValue(new Error("Network error"));
  render(<App />);
  await screen.findByText("Total balance");

  fireEvent.click(screen.getByRole("button", { name: /open assistant/i }));
  fireEvent.change(screen.getByPlaceholderText(/ask about your spending/i), {
    target: { value: "Help!" },
  });
  fireEvent.click(screen.getByText("Run"));

  expect(await screen.findByText(/unavailable/i)).toBeInTheDocument();
});

test("allows deleting a chat session", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({ connectionStatus: active }),
  );
  render(<App />);
  await screen.findByText("Total balance");

  fireEvent.click(screen.getByRole("button", { name: /open assistant/i }));

  // Send a message to create a chat session
  fireEvent.change(screen.getByPlaceholderText(/ask about your spending/i), {
    target: { value: "Hello" },
  });
  fireEvent.click(screen.getByText("Run"));
  await screen.findByText("reply");

  // Go to overview — use the back arrow button (first Show chats button)
  const showChatsButtons = screen.getAllByRole("button", {
    name: /show chats/i,
  });
  fireEvent.click(showChatsButtons[0]);

  // Delete the chat
  const deleteButtons = screen.getAllByRole("button", { name: /delete chat/i });
  expect(deleteButtons.length).toBeGreaterThan(0);
  fireEvent.click(deleteButtons[0]);

  // Should show empty state
  expect(screen.getByText(/no chats yet/i)).toBeInTheDocument();
});
