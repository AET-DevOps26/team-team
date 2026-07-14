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
      totalCreditLimit: 4000,
      utilizationRate: 0.3,
    },
    trend: [
      { month: "Jan", balance: 1000 },
      { month: "Feb", balance: 1200 },
    ],
    expenses: [{ category: "Utilities", percentage: 40 }],
    aiSummary: "Summary",
    connectionStatus: null,
    ...overrides,
  };
}

const active = { status: "ACTIVE", bankName: "Nordea", country: "FI" };

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

test("signs out back to the login page", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({ connectionStatus: active }),
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
