import type { FormEvent} from "react";
import { useEffect, useMemo, useState } from "react";

import type {
  DashboardPayload,
  BankListItem} from "./api";
import {
  fetchDashboard,
  sendChat,
  fetchBanks,
  connectBank,
  handleBankCallback
} from "./api";

const DEFAULT_ACCOUNT_UUID = "11111111-1111-1111-1111-111111111111";
const FRIENDLY_ACCOUNT_ALIAS = "111-222";

function formatMoney(value: number): string {
  return new Intl.NumberFormat("en-US", { style: "currency", currency: "EUR", maximumFractionDigits: 0 }).format(value);
}

function resolveAccountId(): string {
  const queryAccountId = new URLSearchParams(window.location.search).get("accountId");
  const configured = queryAccountId || import.meta.env.VITE_ACCOUNT_ID || FRIENDLY_ACCOUNT_ALIAS;

  return configured === FRIENDLY_ACCOUNT_ALIAS ? DEFAULT_ACCOUNT_UUID : configured;
}

function App() {
  const [data, setData] = useState<DashboardPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [chatInput, setChatInput] = useState("");
  const [chatReply, setChatReply] = useState("");
  const [bankPickerOpen, setBankPickerOpen] = useState(false);
  const [banks, setBanks] = useState<BankListItem[]>([]);
  const [selectedCountry, setSelectedCountry] = useState("DE");
  const [connecting, setConnecting] = useState(false);
  const accountId = useMemo(resolveAccountId, []);

  useEffect(() => {
    if (!accountId) {
      setError(
        "Missing accountId. Set VITE_ACCOUNT_ID or use ?accountId=<uuid> in URL.",
      );
      setLoading(false);

      return;
    }

    fetchDashboard(accountId)
      .then((payload) => {
        setData(payload);
        setLoading(false);
      })
      .catch((e: Error) => {
        setError(e.message);
        setLoading(false);
      });
  }, [accountId]);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const code = params.get("code");
    const state = params.get("state");
    if (!code || !state) {return;}

    // Remove one-time OAuth params right away (but keep other params like accountId)
    params.delete("code");
    params.delete("state");
    const query = params.toString();
    window.history.replaceState({}, "", query ? `${window.location.pathname}?${query}` : window.location.pathname);

    setLoading(true);
    handleBankCallback(code, state)
      .then(() => fetchDashboard(accountId))
      .then((payload) => {
        setData(payload);
        setLoading(false);
      })
      .catch(() => {
        setError("Bank connection failed. Please try again.");
        setLoading(false);
      });
  }, [accountId]);

  const chartPoints = useMemo(() => {
    if (!data?.trend?.length) {
      return "";
    }
    const max = Math.max(...data.trend.map((p) => p.balance));
    const min = Math.min(...data.trend.map((p) => p.balance));
    const spread = Math.max(1, max - min);

    return data.trend
      .map((p, index) => {
        const x = (index / Math.max(1, data.trend.length - 1)) * 100;
        const y = 100 - ((p.balance - min) / spread) * 100;

        return `${x},${y}`;
      })
      .join(" ");
  }, [data]);

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!chatInput.trim()) {
      return;
    }
    setChatReply("Thinking...");
    try {
      const reply = await sendChat(chatInput);
      setChatReply(reply);
    } catch {
      setChatReply("Assistant is unavailable right now.");
    }
  };

  if (loading) {
    return (
      <main className="shell">
        <section className="glass">Loading dashboard...</section>
      </main>
    );
  }

  if (error || !data) {
    return (
      <main className="shell">
        <section className="glass">{error || "Unexpected error"}</section>
      </main>
    );
  }

  const isDemo = data.connectionStatus?.status !== "ACTIVE";

  return (
    <main className="shell">
      <header className="topbar glass">
        <div>
          <p className="brand">Home Banking Assistant</p>
          <h1>Dashboard Overview</h1>
        </div>
        <div style={{ textAlign: "right" }}>
          <p className="muted">Customer: {data.account.customerName} | Account: {FRIENDLY_ACCOUNT_ALIAS}</p>
          {isDemo && <span className="demo-badge">Demo Data</span>}
        </div>
      </header>

      {isDemo && (
        <section className="demo-banner glass">
          You are viewing sample data. Connect your bank below to see real balances and transactions.
        </section>
      )}

      <section className="cards">
        <article className={`card glass${isDemo ? " card--demo" : ""}`}>
          <p>Total Balance {isDemo && <span className="demo-tag">sample</span>}</p>
          <h2>{formatMoney(data.account.totalBalance)}</h2>
        </article>
        <article className={`card glass${isDemo ? " card--demo" : ""}`}>
          <p>Total Credit Limit {isDemo && <span className="demo-tag">sample</span>}</p>
          <h2>{formatMoney(data.account.totalCreditLimit)}</h2>
        </article>
        <article className={`card glass${isDemo ? " card--demo" : ""}`}>
          <p>Utilization Rate {isDemo && <span className="demo-tag">sample</span>}</p>
          <h2>{(data.account.utilizationRate * 100).toFixed(1)}%</h2>
        </article>
      </section>

      <section className="panel glass">
        <h3>Bank Connection</h3>
        {data.connectionStatus?.status === "ACTIVE" ? (
          <p className="connection-active">Connected to <strong>{data.connectionStatus.bankName}</strong> ({data.connectionStatus.country})</p>
        ) : (
          <>
            <p className="muted">Connect your bank account to see real balances and transactions.</p>
            <div className="bank-picker">
              <select value={selectedCountry} onChange={(e) => setSelectedCountry(e.target.value)}>
                <option value="DE">Germany</option>
                <option value="FI">Finland</option>
                <option value="SE">Sweden</option>
                <option value="NL">Netherlands</option>
              </select>
              <button
                onClick={async () => {
                  try {
                    const list = await fetchBanks(selectedCountry);
                    setBanks(list);
                    setBankPickerOpen(true);
                  } catch (e) {
                    setError(e instanceof Error ? e.message : "Failed to load banks");
                  }
                }}
              >
                Load Banks
              </button>
            </div>
            {bankPickerOpen && banks.length > 0 && (
              <ul className="bank-list">
                {banks.map((bank) => (
                  <li key={`${bank.country}:${bank.name}`}>
                    <span className="bank-name">{bank.name}</span>
                    <button
                      disabled={connecting}
                      onClick={async () => {
                        setConnecting(true);
                        try {
                          const { authUrl } = await connectBank(bank.name, bank.country, accountId);
                          window.location.href = authUrl;
                        } catch (e) {
                          setError(e instanceof Error ? e.message : "Failed to initiate connection");
                          setConnecting(false);
                        }
                      }}
                    >
                      {connecting ? "Connecting..." : "Connect"}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </section>

      <section className="split">
        <article className={`panel glass${isDemo ? " panel--demo" : ""}`}>
          <h3>Account Balance Trend {isDemo && <span className="demo-tag">sample</span>}</h3>
          <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="chart">
            <polyline points={chartPoints} />
          </svg>
          <div className="months">
            {data.trend.map((point) => (
              <span key={point.month}>{point.month}</span>
            ))}
          </div>
        </article>

        <article className={`panel glass${isDemo ? " panel--demo" : ""}`}>
          <h3>Expense Categories {isDemo && <span className="demo-tag">sample</span>}</h3>
          <ul className="expense-list">
            {data.expenses.map((slice) => (
              <li key={slice.category}>
                <span>{slice.category}</span>
                <strong>{slice.percentage}%</strong>
              </li>
            ))}
          </ul>
        </article>
      </section>

      <section className="panel glass">
        <h3>AI Summary</h3>
        <p>{data.aiSummary}</p>
      </section>

      <section className="panel glass">
        <h3>Ask Banking Assistant</h3>
        <form onSubmit={onSubmit} className="chat-form">
          <input
            value={chatInput}
            onChange={(e) => setChatInput(e.target.value)}
            placeholder="How can I reduce credit utilization?"
          />
          <button type="submit">Send</button>
        </form>
        {chatReply && <p className="chat-reply">{chatReply}</p>}
      </section>
    </main>
  );
}

export default App;
