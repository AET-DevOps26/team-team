import { FormEvent, useEffect, useMemo, useState } from "react";
import { DashboardPayload, fetchDashboard, sendChat } from "./api";

const DEFAULT_ACCOUNT_UUID = "11111111-1111-1111-1111-111111111111";
const FRIENDLY_ACCOUNT_ALIAS = "111-222";

function formatMoney(value: number): string {
  return new Intl.NumberFormat("en-US", { style: "currency", currency: "USD", maximumFractionDigits: 0 }).format(value);
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
  const accountId = useMemo(resolveAccountId, []);

  useEffect(() => {
    if (!accountId) {
      setError("Missing accountId. Set VITE_ACCOUNT_ID or use ?accountId=<uuid> in URL.");
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
    return <main className="shell"><section className="glass">Loading dashboard...</section></main>;
  }

  if (error || !data) {
    return <main className="shell"><section className="glass">{error || "Unexpected error"}</section></main>;
  }

  return (
    <main className="shell">
      <header className="topbar glass">
        <div>
          <p className="brand">Home Banking Assistant</p>
          <h1>Dashboard Overview</h1>
        </div>
        <p className="muted">Customer: {data.account.customerName} | Account: {FRIENDLY_ACCOUNT_ALIAS}</p>
      </header>

      <section className="cards">
        <article className="card glass">
          <p>Total Balance</p>
          <h2>{formatMoney(data.account.totalBalance)}</h2>
        </article>
        <article className="card glass">
          <p>Total Credit Limit</p>
          <h2>{formatMoney(data.account.totalCreditLimit)}</h2>
        </article>
        <article className="card glass">
          <p>Utilization Rate</p>
          <h2>{(data.account.utilizationRate * 100).toFixed(1)}%</h2>
        </article>
      </section>

      <section className="split">
        <article className="panel glass">
          <h3>Account Balance Trend</h3>
          <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="chart">
            <polyline points={chartPoints} />
          </svg>
          <div className="months">
            {data.trend.map((point) => (
              <span key={point.month}>{point.month}</span>
            ))}
          </div>
        </article>

        <article className="panel glass">
          <h3>Expense Categories</h3>
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
          <input value={chatInput} onChange={(e) => setChatInput(e.target.value)} placeholder="How can I reduce credit utilization?" />
          <button type="submit">Send</button>
        </form>
        {chatReply && <p className="chat-reply">{chatReply}</p>}
      </section>
    </main>
  );
}

export default App;
