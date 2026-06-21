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
      utilizationRate: 0.3
    },
    trend: [
      { month: "Jan", balance: 1000 },
      { month: "Feb", balance: 1200 }
    ],
    expenses: [{ category: "Utilities", percentage: 40 }],
    aiSummary: "Summary",
    connectionStatus: null,
    ...overrides
  };
}

beforeEach(() => {
  window.history.pushState({}, "", "/?accountId=111-222");
  vi.spyOn(api, "sendChat").mockResolvedValue("reply");
});

afterEach(() => {
  vi.restoreAllMocks();
});

test("renders dashboard heading", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(dashboard());
  render(<App />);
  expect(await screen.findByText("Dashboard Overview")).toBeInTheDocument();
});

test("shows the demo banner and badge when no bank is connected", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(dashboard({ connectionStatus: null }));
  render(<App />);

  expect(await screen.findByText(/viewing sample data/i)).toBeInTheDocument();
  expect(screen.getByText("Demo Data")).toBeInTheDocument();
});

test("shows the connected bank and hides the demo banner when status is ACTIVE", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(
    dashboard({ connectionStatus: { status: "ACTIVE", bankName: "Nordea", country: "FI" } })
  );
  render(<App />);

  expect(await screen.findByText(/connected to/i)).toBeInTheDocument();
  expect(screen.getByText("Nordea")).toBeInTheDocument();
  expect(screen.queryByText(/viewing sample data/i)).not.toBeInTheDocument();
});

test("loads and renders the bank list when Load Banks is clicked", async () => {
  vi.spyOn(api, "fetchDashboard").mockResolvedValue(dashboard({ connectionStatus: null }));
  const fetchBanks = vi.spyOn(api, "fetchBanks").mockResolvedValue([
    { name: "Bank A", country: "DE" },
    { name: "Bank B", country: "DE" }
  ]);
  render(<App />);
  await screen.findByText("Dashboard Overview");

  fireEvent.click(screen.getByText("Load Banks"));

  const connectButtons = await screen.findAllByText("Connect");
  expect(connectButtons).toHaveLength(2);
  expect(fetchBanks).toHaveBeenCalledWith("DE");

  // each row shows the bank name next to its Connect button
  expect(screen.getByText("Bank A")).toBeInTheDocument();
  expect(screen.getByText("Bank B")).toBeInTheDocument();
});
