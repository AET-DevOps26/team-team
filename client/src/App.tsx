import { FormEvent, useEffect, useMemo, useState } from "react";
import { DashboardPayload, fetchDashboard, sendChat } from "./api";

function formatMoney(value: number): string {
  return new Intl.NumberFormat("en-US", { style: "currency", currency: "USD", maximumFractionDigits: 0 }).format(value);
}

function App() {
  const [data, setData] = useState<DashboardPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [chatInput, setChatInput] = useState("");
  const [chatReply, setChatReply] = useState("");

  useEffect(() => {
    fetchDashboard()
      .then((payload) => {
        setData(payload);
        setLoading(false);
      })
      .catch((e: Error) => {
        setError(e.message);
        setLoading(false);
      });
  }, []);

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
        <p className="muted">Customer: {data.account.customerName}</p>
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
