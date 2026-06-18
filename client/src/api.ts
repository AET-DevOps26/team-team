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

export interface BankListItem {
  name: string;
  country: string;
}

export interface ConnectionStatus {
  status: string;
  bankName: string | null;
  country: string | null;
}

export interface DashboardPayload {
  account: AccountSummary;
  trend: BalancePoint[];
  expenses: ExpenseSlice[];
  aiSummary: string;
  connectionStatus: ConnectionStatus | null;
}

const API_BASE = import.meta.env.VITE_API_BASE_URL || "";

export async function fetchDashboard(
  accountId: string,
): Promise<DashboardPayload> {
  const response = await fetch(`${API_BASE}/api/dashboard/${accountId}`);
  if (!response.ok) {
    throw new Error("Failed to load dashboard");
  }

  return response.json() as Promise<DashboardPayload>;
}

export async function fetchBanks(country: string): Promise<BankListItem[]> {
  const response = await fetch(`${API_BASE}/api/banking/banks?country=${country}`);
  if (!response.ok) throw new Error("Failed to load banks");
  return response.json() as Promise<BankListItem[]>;
}

export async function connectBank(bankName: string, country: string, accountId: string): Promise<{ authUrl: string }> {
  const response = await fetch(`${API_BASE}/api/banking/connect`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ bankName, country, accountId })
  });
  if (!response.ok) throw new Error("Failed to initiate connection");
  return response.json() as Promise<{ authUrl: string }>;
}

export async function handleBankCallback(code: string, state: string): Promise<ConnectionStatus> {
  const response = await fetch(`${API_BASE}/api/banking/callback`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, state })
  });
  if (!response.ok) throw new Error("Bank connection failed");
  return response.json() as Promise<ConnectionStatus>;
}

export async function sendChat(message: string): Promise<string> {
  const response = await fetch(`${API_BASE}/api/chat`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ message }),
  });
  if (!response.ok) {
    throw new Error("Failed to send message");
  }
  const data = (await response.json()) as { reply: string };

  return data.reply;
}
