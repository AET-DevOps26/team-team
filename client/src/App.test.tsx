import { render, screen, fireEvent } from "@testing-library/react";

import * as api from "./api";
import type { DashboardPayload } from "./api";
import App from "./App";

function dashboard(overrides: Partial<DashboardPayload> = {}): DashboardPayload {
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
  vi.spyOn(api, "sendChat").mockResolvedValue({ reply: "reply", reasoning: null });
});

afterEach(() => {
  vi.restoreAllMocks();
  sessionStorage.clear();
});

test("shows the login page and signs in with admin/admin", async () => {
  sessionStorage.clear();
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(dashboard({ connectionStatus: null }));
  render(<App />);

  expect(screen.getByRole("heading", { name: "Sign in" })).toBeInTheDocument();
  fireEvent.change(screen.getByPlaceholderText("Username"), { target: { value: "admin" } });
  fireEvent.change(screen.getByPlaceholderText("Password"), { target: { value: "admin" } });
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

  expect(await screen.findByText("No banks connected")).toBeInTheDocument();
});

test("shows the empty state when no bank is connected", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(dashboard({ connectionStatus: null }));
  render(<App />);

  expect(await screen.findByText("No banks connected")).toBeInTheDocument();
  // seeded totals must not leak through before a bank is linked
  expect(screen.queryByText("Total balance")).not.toBeInTheDocument();
});

test("shows the live dashboard and roster when a bank is ACTIVE", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(dashboard({ connectionStatus: active }));
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

test("signs out back to the login page", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(dashboard({ connectionStatus: active }));
  render(<App />);
  await screen.findByText("Total balance");

  fireEvent.click(screen.getByRole("button", { name: "Sign out" }));

  expect(screen.getByRole("heading", { name: "Sign in" })).toBeInTheDocument();
});

test("sends a chat message and shows the reply", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(dashboard({ connectionStatus: active }));
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
