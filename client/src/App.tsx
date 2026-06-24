import type { FormEvent } from "react";
import { useEffect, useMemo, useState } from "react";

import type { DashboardPayload, BankListItem, BalancePoint } from "./api";
import {
  fetchDashboard,
  sendChat,
  fetchBanks,
  connectBank,
  handleBankCallback,
} from "./api";

const DEFAULT_ACCOUNT_UUID = "11111111-1111-1111-1111-111111111111";
const FRIENDLY_ACCOUNT_ALIAS = "111-222";

const COUNTRIES = [
  { code: "DE", name: "Germany" },
  { code: "FI", name: "Finland" },
  { code: "SE", name: "Sweden" },
  { code: "NL", name: "Netherlands" },
];

// SVG trend geometry (0..100 wide, 0..VIEW_H tall; the line sits between Y_TOP and Y_BOTTOM)
const VIEW_H = 40;
const Y_TOP = 5;
const Y_BOTTOM = 35;
const MIN_TREND_POINTS = 2;

function formatMoney(value: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "EUR",
    maximumFractionDigits: 0,
  }).format(value);
}

function resolveAccountId(): string {
  const queryAccountId = new URLSearchParams(window.location.search).get("accountId");
  const configured = queryAccountId || import.meta.env.VITE_ACCOUNT_ID || FRIENDLY_ACCOUNT_ALIAS;

  return configured === FRIENDLY_ACCOUNT_ALIAS ? DEFAULT_ACCOUNT_UUID : configured;
}

/* Country picker + bank list -> Enable Banking OAuth redirect.
   Shared by the empty state ("connect your first bank") and the live
   "add a bank" action, so connecting looks identical in both places. */
function BankConnect({ accountId }: { accountId: string }) {
  const [country, setCountry] = useState("DE");
  const [banks, setBanks] = useState<BankListItem[]>([]);
  const [listed, setListed] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const onFind = async () => {
    setError(null);
    try {
      setBanks(await fetchBanks(country));
      setListed(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load banks");
    }
  };

  const onConnect = async (bank: BankListItem) => {
    setConnecting(true);
    setError(null);
    try {
      const { authUrl } = await connectBank(bank.name, bank.country, accountId);
      window.location.href = authUrl;
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to initiate connection");
      setConnecting(false);
    }
  };

  return (
    <>
      <div className="picker">
        <select aria-label="Country" value={country} onChange={(e) => setCountry(e.target.value)}>
          {COUNTRIES.map((c) => (
            <option key={c.code} value={c.code}>
              {c.name}
            </option>
          ))}
        </select>
        <button className="find" onClick={onFind}>
          Find banks
        </button>
      </div>

      {error && <p className="conn-msg error">{error}</p>}

      {listed && banks.length > 0 && (
        <ul className="banklist">
          {banks.map((bank) => (
            <li key={`${bank.country}:${bank.name}`}>
              <span className="bn">{bank.name}</span>
              <button className="link" disabled={connecting} onClick={() => onConnect(bank)}>
                {connecting ? "Connecting…" : "Connect"}
              </button>
            </li>
          ))}
        </ul>
      )}

      {listed && banks.length === 0 && !error && (
        <p className="conn-msg">No banks available for that country.</p>
      )}
    </>
  );
}

function TrendChart({ trend }: { trend: BalancePoint[] }) {
  const chart = useMemo(() => {
    if (trend.length < MIN_TREND_POINTS) {
      return null;
    }
    const balances = trend.map((p) => p.balance);
    const max = Math.max(...balances);
    const min = Math.min(...balances);
    const spread = Math.max(1, max - min);
    const points = trend.map((p, index) => {
      const x = (index / (trend.length - 1)) * 100;
      const y = Y_TOP + (1 - (p.balance - min) / spread) * (Y_BOTTOM - Y_TOP);

      return [x, y] as const;
    });
    const last = points[points.length - 1];

    return {
      points: points.map((q) => `${q[0]},${q[1]}`).join(" "),
      dotLeft: `${last[0]}%`,
      dotTop: `${(last[1] / VIEW_H) * 100}%`,
      months: trend.map((p) => p.month),
    };
  }, [trend]);

  if (!chart) {
    return null;
  }

  return (
    <>
      <hr className="divide" />
      <section className="pad">
        <p className="seclabel">
          Combined balance <span className="n">{chart.months.length}M</span>
        </p>
        <div className="plot">
          <svg viewBox="0 0 100 40" preserveAspectRatio="none" aria-hidden="true">
            <polyline className="line" points={chart.points} />
          </svg>
          <i className="enddot" style={{ left: chart.dotLeft, top: chart.dotTop }} />
        </div>
        <div className="axis">
          {chart.months.map((m) => (
            <span key={m}>{m}</span>
          ))}
        </div>
      </section>
    </>
  );
}

function Dashboard({
  data,
  accountId,
  onLogout,
}: {
  data: DashboardPayload;
  accountId: string;
  onLogout: () => void;
}) {
  const [chatInput, setChatInput] = useState("");
  const [chatReply, setChatReply] = useState("");
  const [addOpen, setAddOpen] = useState(false);

  const trendDelta =
    data.trend.length >= MIN_TREND_POINTS
      ? data.trend[data.trend.length - 1].balance - data.trend[0].balance
      : null;

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!chatInput.trim()) {
      return;
    }
    setChatReply("Thinking…");
    try {
      setChatReply(await sendChat(chatInput));
    } catch {
      setChatReply("Assistant is unavailable right now.");
    }
  };

  return (
    <main className="term">
      <header className="statusbar">
        <span className="live">
          <span className="dot" />1 bank linked
        </span>
        <span className="right">
          {data.account.customerName}
          <button className="signout" onClick={onLogout}>
            Sign out
          </button>
        </span>
      </header>

      <section className="hero">
        <p className="k">Total balance</p>
        <p className="v">{formatMoney(data.account.totalBalance)}</p>
        <p className="sub">
          Across 1 bank
          {trendDelta !== null && (
            <>
              {" · "}
              <span className={trendDelta >= 0 ? "up" : "down"}>
                {trendDelta >= 0 ? "+" : "−"}
                {formatMoney(Math.abs(trendDelta))}
              </span>{" "}
              over {data.trend.length} months
            </>
          )}
        </p>
      </section>

      <hr className="divide" />

      <div className="pair">
        <div className="cell">
          <p className="k">Credit limit</p>
          <p className="v">{formatMoney(data.account.totalCreditLimit)}</p>
        </div>
        <div className="cell">
          <p className="k">Utilization</p>
          <p className="v">{(data.account.utilizationRate * 100).toFixed(1)}%</p>
        </div>
      </div>

      <hr className="divide" />

      <section className="pad">
        <p className="seclabel">
          Linked banks <span className="n">1</span>
        </p>
        <ul className="roster">
          <li>
            <span className="dot" />
            <span className="bn">
              {data.connectionStatus?.bankName}{" "}
              {data.connectionStatus?.country && (
                <span className="cc">{data.connectionStatus.country}</span>
              )}
            </span>
            <span className="st">Live</span>
          </li>
        </ul>
        <div className="add">
          <button className="add-btn" onClick={() => setAddOpen((v) => !v)}>
            <span className="plus">+</span>Add a bank
          </button>
          {addOpen && <BankConnect accountId={accountId} />}
        </div>
      </section>

      <TrendChart trend={data.trend} />

      {data.expenses.length > 0 && (
        <>
          <hr className="divide" />
          <section className="pad">
            <p className="seclabel">
              Expenses <span className="n">all banks</span>
            </p>
            <div>
              {data.expenses.map((slice) => (
                <div className="erow" key={slice.category}>
                  <span className="cat">{slice.category}</span>
                  <span className="track">
                    <i style={{ width: `${slice.percentage}%` }} />
                  </span>
                  <span className="pct">{slice.percentage}%</span>
                </div>
              ))}
            </div>
          </section>
        </>
      )}

      {data.aiSummary && (
        <>
          <hr className="divide" />
          <section className="pad summary">
            <p className="seclabel">Summary</p>
            <p>{data.aiSummary}</p>
          </section>
        </>
      )}

      <hr className="divide" />

      <section className="pad repl">
        {chatReply && <p className="out">{chatReply}</p>}
        <form className="prompt" onSubmit={onSubmit}>
          <span className="chev">›</span>
          <input
            aria-label="Ask the banking assistant"
            value={chatInput}
            onChange={(e) => setChatInput(e.target.value)}
            placeholder="ask about your spending, limits, or balance"
          />
          <button className="run" type="submit">
            Run
          </button>
        </form>
      </section>
    </main>
  );
}

function Login({ onSuccess }: { onSuccess: () => void }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (username === "admin" && password === "admin") {
      sessionStorage.setItem("authed", "1");
      onSuccess();
    } else {
      setError("Incorrect username or password.");
    }
  };

  return (
    <main className="term">
      <section className="empty">
        <p className="mark">Home banking</p>
        <h2>Sign in</h2>
        <form className="login" onSubmit={onSubmit}>
          <input
            aria-label="Username"
            type="text"
            placeholder="Username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
          <input
            aria-label="Password"
            type="password"
            placeholder="Password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <button className="find" type="submit">
            Sign in
          </button>
        </form>
        {error && <p className="conn-msg error">{error}</p>}
      </section>
    </main>
  );
}

function App() {
  const [authed, setAuthed] = useState(() => sessionStorage.getItem("authed") === "1");
  const [data, setData] = useState<DashboardPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
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

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const code = params.get("code");
    const state = params.get("state");
    if (!code || !state) {
      return;
    }

    // Remove one-time OAuth params right away (but keep other params like accountId)
    params.delete("code");
    params.delete("state");
    const query = params.toString();
    window.history.replaceState(
      {},
      "",
      query ? `${window.location.pathname}?${query}` : window.location.pathname,
    );

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

  if (!authed) {
    return <Login onSuccess={() => setAuthed(true)} />;
  }

  if (loading) {
    return (
      <main className="term">
        <p className="notice">Connecting…</p>
      </main>
    );
  }

  if (error || !data) {
    return (
      <main className="term">
        <p className="notice error">{error || "Unexpected error"}</p>
      </main>
    );
  }

  // No real data until a bank is actually linked — empty state over fake content.
  if (data.connectionStatus?.status !== "ACTIVE") {
    return (
      <main className="term">
        <section className="empty">
          <p className="mark">Home banking</p>
          <h2>No banks connected</h2>
          <p>
            Connect your first bank to start aggregating balances, limits and spending. Add as
            many as you like — nothing is shown until there’s real data.
          </p>
          <BankConnect accountId={accountId} />
        </section>
      </main>
    );
  }

  const onLogout = () => {
    sessionStorage.removeItem("authed");
    setAuthed(false);
  };

  return <Dashboard data={data} accountId={accountId} onLogout={onLogout} />;
}

export default App;
