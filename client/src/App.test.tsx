import { render, screen } from "@testing-library/react";
import App from "./App";
import * as api from "./api";

vi.spyOn(api, "fetchDashboard").mockResolvedValue({
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
  aiSummary: "Summary"
});

vi.spyOn(api, "sendChat").mockResolvedValue("reply");

test("renders dashboard heading", async () => {
  window.history.pushState({}, "", "/?accountId=111-222");
  render(<App />);
  expect(await screen.findByText("Dashboard Overview")).toBeInTheDocument();
});
