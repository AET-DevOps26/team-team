export interface BalancePoint {
  month: string;
  balance: number;
}

export interface ExpenseSlice {
  category: string;
  percentage: number;
}

export interface AccountSummary {
  accountId: string;
  customerName: string;
  totalBalance: number;
  totalCreditLimit: number;
  utilizationRate: number;
}

export interface DashboardPayload {
  account: AccountSummary;
  trend: BalancePoint[];
  expenses: ExpenseSlice[];
  aiSummary: string;
}

const API_BASE = import.meta.env.VITE_API_BASE_URL || "http://localhost:8083";
const ACCOUNT_ID = "11111111-1111-1111-1111-111111111111";

export async function fetchDashboard(): Promise<DashboardPayload> {
  const response = await fetch(`${API_BASE}/api/dashboard/${ACCOUNT_ID}`);
  if (!response.ok) {
    throw new Error("Failed to load dashboard");
  }
  return response.json() as Promise<DashboardPayload>;
}

export async function sendChat(message: string): Promise<string> {
  const response = await fetch(`${API_BASE}/api/chat`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ message })
  });
  if (!response.ok) {
    throw new Error("Failed to send message");
  }
  const data = (await response.json()) as { reply: string };
  return data.reply;
}
