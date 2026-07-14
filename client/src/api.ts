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

export interface Connection {
  status: string;
  bankName: string | null;
  country: string | null;
  accountName: string | null;
  balance: number | null;
  currency: string | null;
}

export interface Transaction {
  id: string;
  category: string;
  amount: number;
  direction: string;
  bankName: string | null;
  counterparty: string | null;
  createdAt: string;
}

export interface MonthlyFlow {
  month: string;
  income: number;
  spending: number;
  net: number;
}

export interface BankSpend {
  bankName: string;
  spending: number;
}

export interface DashboardPayload {
  account: AccountSummary;
  trend: BalancePoint[];
  expenses: ExpenseSlice[];
  aiSummary: string;
  connectionStatus: ConnectionStatus | null;
  connections: Connection[];
  transactions: Transaction[];
  monthlyFlow: MonthlyFlow | null;
  spendByBank: BankSpend[];
}

const API_BASE = import.meta.env.VITE_API_BASE_URL || "";

export async function fetchDashboard(
  accountId: string,
): Promise<DashboardPayload> {
  const response = await fetch(`${API_BASE}/api/dashboard/${accountId}`);
  if (!response.ok) {
    throw new Error("Failed to load dashboard");
  }

  const payload = (await response.json()) as DashboardPayload;

  // Tolerate a backend that predates the multi-bank fields (e.g. during a
  // rolling deploy) so the dashboard renders instead of crashing on `.length`.
  return {
    ...payload,
    connections: payload.connections ?? [],
    transactions: payload.transactions ?? [],
    spendByBank: payload.spendByBank ?? [],
    monthlyFlow: payload.monthlyFlow ?? null,
  };
}

export async function fetchBanks(country: string): Promise<BankListItem[]> {
  const response = await fetch(`${API_BASE}/api/banking/banks?country=${country}`);
  if (!response.ok) {throw new Error("Failed to load banks");}

  return response.json() as Promise<BankListItem[]>;
}

export async function connectBank(bankName: string, country: string, accountId: string): Promise<{ authUrl: string }> {
  const response = await fetch(`${API_BASE}/api/banking/connect`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ bankName, country, accountId })
  });
  if (!response.ok) {throw new Error("Failed to initiate connection");}

  return response.json() as Promise<{ authUrl: string }>;
}

export async function handleBankCallback(code: string, state: string): Promise<ConnectionStatus> {
  const response = await fetch(`${API_BASE}/api/banking/callback`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, state })
  });
  if (!response.ok) {throw new Error("Bank connection failed");}

  return response.json() as Promise<ConnectionStatus>;
}

export async function sendChat(
  messages: ChatMessage[],
  context?: ChatContext,
): Promise<ChatReply> {
  const response = await fetch(`${API_BASE}/api/chat`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ messages, context }),
  });
  if (!response.ok) {
    throw new Error("Failed to send message");
  }

  return response.json() as Promise<ChatReply>;
}

export interface ChatMessage {
  role: "user" | "assistant" | "system";
  content: string;
}

export interface ChatReply {
  reply: string;
  reasoning: string | null;
}

export type ChatContext = Pick<
  DashboardPayload,
  "account" | "trend" | "expenses" | "connections" | "transactions" | "monthlyFlow"
>;
