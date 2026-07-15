import { render, screen, fireEvent } from "@testing-library/react";

import * as api from "./api";
import type { DashboardPayload, AppUser } from "./api";
import App from "./App";

function dashboard(overrides: Partial<DashboardPayload> = {}): DashboardPayload {
  return {
    account: {
      accountId: "1",
      customerName: "Test User",
      totalBalance: 1200,
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

const REAL_ACCOUNT_UUID = "22222222-2222-2222-2222-222222222222";
const DEMO_ACCOUNT_UUID = "11111111-1111-1111-1111-111111111111";

const testUser: AppUser = {
  githubId: 1,
  login: "testuser",
  firstName: "Test",
  lastName: "Person",
  email: "test@example.com",
  avatarUrl: null,
  accountId: REAL_ACCOUNT_UUID,
};

// Skip the login gate for dashboard tests by pre-seeding a stored session and
// short-circuiting the /me revalidation call.
function primeSignedIn(user: AppUser = testUser) {
  localStorage.setItem("auth.token", "test-token");
  localStorage.setItem("auth.user", JSON.stringify(user));
  vi.spyOn(api, "fetchCurrentUser").mockResolvedValue(user);
}

beforeEach(() => {
  window.history.pushState({}, "", "/");
  localStorage.clear();
  sessionStorage.clear();
  primeSignedIn();
  vi.spyOn(api, "sendChat").mockResolvedValue({ reply: "reply", reasoning: null });
});

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
  sessionStorage.clear();
});

test("shows the login page with a GitHub sign-in button when unauthenticated", async () => {
  localStorage.clear(); // remove the primed session
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(dashboard({ connectionStatus: null }));
  render(<App />);

  expect(screen.getByRole("heading", { name: "Sign in" })).toBeInTheDocument();
  expect(
    screen.getByRole("button", { name: /sign in with github/i }),
  ).toBeInTheDocument();
});

test("clicking the GitHub sign-in button redirects to the returned authorize URL", async () => {
  localStorage.clear();
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(dashboard({ connectionStatus: null }));
  vi.spyOn(api, "startGithubLogin").mockResolvedValue({
    authUrl: "https://github.example/authorize?state=abc",
    state: "abc",
  });
  // jsdom's window.location.href is not directly assignable in a spy-friendly way,
  // so intercept navigation via the setter on the descriptor.
  const originalLocation = window.location;
  const hrefSpy = vi.fn();
  Object.defineProperty(window, "location", {
    configurable: true,
    value: { ...originalLocation, assign: hrefSpy, set href(v: string) { hrefSpy(v); }, get href() { return originalLocation.href; } },
  });

  render(<App />);
  fireEvent.click(screen.getByRole("button", { name: /sign in with github/i }));

  await vi.waitFor(() =>
    expect(hrefSpy).toHaveBeenCalledWith("https://github.example/authorize?state=abc"),
  );

  Object.defineProperty(window, "location", { configurable: true, value: originalLocation });
});

test("shows the empty state when no bank is connected", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(dashboard({ connectionStatus: null }));
  render(<App />);

  expect(await screen.findByText("No banks connected")).toBeInTheDocument();
  // seeded totals must not leak through before a bank is linked
  expect(screen.queryByText("Total balance")).not.toBeInTheDocument();
});

test("shows the live dashboard and roster when a bank is ACTIVE", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(dashboard({ connectionStatus: active, connections: activeConnections }));
  render(<App />);

  expect(await screen.findByText("Total balance")).toBeInTheDocument();
  expect(screen.getByText("Nordea")).toBeInTheDocument();
  expect(screen.queryByText("No banks connected")).not.toBeInTheDocument();
});

test("loads and renders the bank list when Find banks is clicked", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(dashboard({ connectionStatus: null }));
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
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(dashboard({ connectionStatus: null }));
  vi.spyOn(api, "fetchBanks").mockResolvedValue([
    { name: "Bank A", country: "DE" },
    { name: "Bank B", country: "DE" },
  ]);
  render(<App />);
  await screen.findByText("No banks connected");

  fireEvent.click(screen.getByText("Find banks"));
  await screen.findByText("Bank A");

  // Filter narrows the list to matching banks.
  fireEvent.change(screen.getByLabelText("Filter banks"), { target: { value: "Bank A" } });
  expect(screen.getByText("Bank A")).toBeInTheDocument();
  expect(screen.queryByText("Bank B")).not.toBeInTheDocument();

  // Close collapses back to just the picker.
  fireEvent.click(screen.getByLabelText("Close bank list"));
  expect(screen.queryByText("Bank A")).not.toBeInTheDocument();
  expect(screen.getByText("Find banks")).toBeInTheDocument();
});

// fetchDashboard mock that serves the demo account (mock ASPSP data) or the
// real account (production EB data) depending on the requested id.
function mockDashboardByAccount() {
  return vi.spyOn(api, "fetchDashboard").mockImplementation(async (id) =>
    id === DEMO_ACCOUNT_UUID
      ? dashboard({
          account: { accountId: DEMO_ACCOUNT_UUID, customerName: "Mock Holder", totalBalance: 500 },
          connectionStatus: { status: "ACTIVE", bankName: "Mock ASPSP", country: "FI" },
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

  // Defaults to live: the real account is fetched, so the live bank shows.
  expect(await screen.findByText("Nordea")).toBeInTheDocument();
  expect(fetchDashboard).toHaveBeenCalledWith(REAL_ACCOUNT_UUID);

  // Flip to demo: the demo account (mock ASPSP data) replaces it, persisted.
  fireEvent.click(screen.getByRole("button", { name: "Demo" }));
  expect(await screen.findByText("Mock ASPSP")).toBeInTheDocument();
  expect(screen.queryByText("Nordea")).not.toBeInTheDocument();
  expect(fetchDashboard).toHaveBeenCalledWith(DEMO_ACCOUNT_UUID);
  expect(localStorage.getItem("dashboard.mode")).toBe("demo");

  // Flip back to live: the real bank returns.
  fireEvent.click(screen.getByRole("button", { name: "Live" }));
  expect(await screen.findByText("Nordea")).toBeInTheDocument();
});

test("demo mode only fetches the demo account, never the real one", async () => {
  localStorage.setItem("dashboard.mode", "demo");
  const fetchDashboard = mockDashboardByAccount();
  render(<App />);

  expect(await screen.findByText("Mock ASPSP")).toBeInTheDocument();
  expect(fetchDashboard).toHaveBeenCalledWith(DEMO_ACCOUNT_UUID);
  expect(fetchDashboard).not.toHaveBeenCalledWith(REAL_ACCOUNT_UUID);
});

test("signs out back to the login page", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(dashboard({ connectionStatus: active, connections: activeConnections }));
  render(<App />);
  await screen.findByText("Total balance");

  fireEvent.click(screen.getByRole("button", { name: "Sign out" }));

  expect(screen.getByRole("heading", { name: "Sign in" })).toBeInTheDocument();
});

test("sends a chat message and shows the reply", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(dashboard({ connectionStatus: active, connections: activeConnections }));
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
