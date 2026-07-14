/* eslint-disable react-hooks/set-state-in-effect, react-hooks/purity, react-hooks/use-memo, no-magic-numbers */
import type { FormEvent, MouseEvent as ReactMouseEvent } from "react";
import { useEffect, useMemo, useRef, useState } from "react";

import type {
  DashboardPayload,
  BankListItem,
  BalancePoint,
  ChatContext,
  ChatMessage,
} from "./api";
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
  const queryAccountId = new URLSearchParams(window.location.search).get(
    "accountId",
  );
  const configured =
    queryAccountId || import.meta.env.VITE_ACCOUNT_ID || FRIENDLY_ACCOUNT_ALIAS;

  return configured === FRIENDLY_ACCOUNT_ALIAS
    ? DEFAULT_ACCOUNT_UUID
    : configured;
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
      // eslint-disable-next-line react-hooks/immutability -- intentional OAuth redirect
      window.location.href = authUrl;
    } catch (e) {
      setError(
        e instanceof Error ? e.message : "Failed to initiate connection",
      );
      setConnecting(false);
    }
  };

  return (
    <>
      <div className="picker">
        <select
          aria-label="Country"
          value={country}
          onChange={(e) => setCountry(e.target.value)}
        >
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
              <button
                className="link"
                disabled={connecting}
                onClick={() => onConnect(bank)}
              >
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
          <svg
            viewBox={`0 0 100 ${VIEW_H}`}
            preserveAspectRatio="none"
            aria-hidden="true"
          >
            <polyline className="line" points={chart.points} />
          </svg>
          <i
            className="enddot"
            style={{ left: chart.dotLeft, top: chart.dotTop }}
          />
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

/* ------------------------------------------------------------------ */
/* Chat panel — sessions in localStorage, per-message reasoning drawer, */
/* auto-injected dashboard context so the model can answer WorkIQ-style */
/* questions about the user's balances, expenses and linked banks.     */
/* ------------------------------------------------------------------ */

interface ChatSession {
  id: string;
  title: string;
  createdAt: number;
  messages: ChatTurn[];
}

interface ChatTurn extends ChatMessage {
  id: string;
  reasoning?: string | null;
  pending?: boolean;
}

const CHAT_STORAGE_KEY = "chat.sessions.v1";
const MAX_HISTORY = 20;

// Starter prompts shown in the empty chat state — clicking one sends it
// straight to the assistant, so users can explore without typing.
const SUGGESTED_PROMPTS = [
  "What's driving my utilization above 100%?",
  "Summarize my spending this month.",
  "How has my balance moved over the last 6 months?",
];

function sessionsStorageKey(accountId: string): string {
  return `${CHAT_STORAGE_KEY}:${accountId}`;
}

function loadSessions(accountId: string): ChatSession[] {
  try {
    const raw = localStorage.getItem(sessionsStorageKey(accountId));
    if (!raw) {
      return [];
    }
    const parsed = JSON.parse(raw) as ChatSession[];

    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function saveSessions(accountId: string, sessions: ChatSession[]): void {
  try {
    localStorage.setItem(
      sessionsStorageKey(accountId),
      JSON.stringify(sessions),
    );
  } catch {
    // storage quota / disabled — chat still works, just no persistence.
  }
}

function newSession(): ChatSession {
  return {
    id: crypto.randomUUID?.() ?? String(Date.now()),
    title: "New chat",
    createdAt: Date.now(),
    messages: [],
  };
}

function deriveTitle(text: string): string {
  const clean = text.trim().replace(/\s+/g, " ");

  return clean.length > 40 ? `${clean.slice(0, 40)}…` : clean || "New chat";
}

function buildContext(data: DashboardPayload): ChatContext {
  return {
    account: data.account,
    trend: data.trend,
    expenses: data.expenses,
    connection: data.connectionStatus,
  };
}

type ChatView = "overview" | "chat";

// eslint-disable-next-line max-lines-per-function
function ChatPanel({
  data,
  accountId,
  onClose,
}: {
  data: DashboardPayload;
  accountId: string;
  onClose?: () => void;
}) {
  // On mount: if the user has prior chats, show the overview (like VS Code's
  // chat history view). Otherwise drop straight into a fresh chat so the
  // input is immediately usable.
  const initialSessions = useMemo(() => loadSessions(accountId), [accountId]);
  const [sessions, setSessions] = useState<ChatSession[]>(() =>
    initialSessions.length > 0 ? initialSessions : [newSession()],
  );
  const [activeId, setActiveId] = useState<string>(() =>
    initialSessions.length > 0 ? initialSessions[0].id : sessions[0].id,
  );
  const [view, setView] = useState<ChatView>(() =>
    initialSessions.length > 0 ? "overview" : "chat",
  );
  const [input, setInput] = useState("");
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const listRef = useRef<HTMLDivElement | null>(null);

  const active = sessions.find((s) => s.id === activeId);

  useEffect(() => {
    saveSessions(accountId, sessions);
  }, [accountId, sessions]);

  useEffect(() => {
    if (view === "chat" && listRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight;
    }
  }, [view, active?.messages.length]);

  const patchSession = (
    id: string,
    update: (s: ChatSession) => ChatSession,
  ) => {
    setSessions((prev) => prev.map((s) => (s.id === id ? update(s) : s)));
  };

  const openChat = (id: string) => {
    setActiveId(id);
    setView("chat");
    setInput("");
  };

  const showOverview = () => {
    setView("overview");
  };

  const onNewChat = () => {
    const fresh = newSession();
    setSessions((prev) => [fresh, ...prev]);
    setActiveId(fresh.id);
    setView("chat");
    setInput("");
  };

  // Deletes any chat. When it's the one currently open, drop back to the
  // overview so the user can pick another or start fresh.
  const onDeleteChat = (id: string) => {
    setSessions((prev) => prev.filter((s) => s.id !== id));
    if (id === activeId) {
      setActiveId("");
      setView("overview");
    }
  };

  const sendMessage = async (raw: string) => {
    const text = raw.trim();
    if (!text || !active) {
      return;
    }

    const userTurn: ChatTurn = {
      id: crypto.randomUUID?.() ?? `u-${Date.now()}`,
      role: "user",
      content: text,
    };
    const pendingId = crypto.randomUUID?.() ?? `a-${Date.now()}`;
    const pendingTurn: ChatTurn = {
      id: pendingId,
      role: "assistant",
      content: "",
      pending: true,
    };

    patchSession(active.id, (s) => ({
      ...s,
      title: s.messages.length === 0 ? deriveTitle(text) : s.title,
      messages: [...s.messages, userTurn, pendingTurn],
    }));
    setInput("");

    // Only forward role/content to the backend, capped to the recent window.
    const history: ChatMessage[] = [...active.messages, userTurn]
      .slice(-MAX_HISTORY)
      .map(({ role, content }) => ({ role, content }));

    try {
      const reply = await sendChat(history, buildContext(data));
      patchSession(active.id, (s) => ({
        ...s,
        messages: s.messages.map((m) =>
          m.id === pendingId
            ? {
                ...m,
                content: reply.reply,
                reasoning: reply.reasoning,
                pending: false,
              }
            : m,
        ),
      }));
    } catch {
      patchSession(active.id, (s) => ({
        ...s,
        messages: s.messages.map((m) =>
          m.id === pendingId
            ? {
                ...m,
                content: "Assistant is unavailable right now.",
                pending: false,
              }
            : m,
        ),
      }));
    }
  };

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    void sendMessage(input);
  };

  return (
    <section className="pad chat">
      <div className="chat-head">
        <div className="chat-head-left">
          {view === "chat" && (
            <button
              type="button"
              className="chat-icon-btn"
              onClick={showOverview}
              title="Show chats"
              aria-label="Show chats"
            >
              ←
            </button>
          )}
          <p className="seclabel chat-title">
            {view === "overview" ? (
              <>
                Chats <span className="n">{sessions.length}</span>
              </>
            ) : (
              <span className="chat-active-title">
                {active?.title ?? "New chat"}
              </span>
            )}
          </p>
        </div>
        <div className="chat-tools">
          <button
            type="button"
            className="chat-icon-btn"
            onClick={onNewChat}
            title="New chat"
            aria-label="New chat"
          >
            +
          </button>
          {view === "chat" && (
            <button
              type="button"
              className="chat-icon-btn"
              onClick={showOverview}
              title="Show chats"
              aria-label="Show chats"
            >
              ☰
            </button>
          )}
          {view === "chat" && active && (
            <button
              type="button"
              className="chat-icon-btn"
              onClick={() => onDeleteChat(active.id)}
              title="Delete chat"
              aria-label="Delete chat"
            >
              🗑
            </button>
          )}
          {onClose && (
            <>
              <span className="chat-tool-sep" aria-hidden="true" />
              <button
                type="button"
                className="chat-icon-btn chat-close"
                onClick={onClose}
                title="Close chat panel (Esc)"
                aria-label="Close chat panel"
              >
                ×
              </button>
            </>
          )}
        </div>
      </div>

      {view === "overview" ? (
        <div className="chat-overview">
          {sessions.length === 0 ? (
            <div className="chat-empty">
              <p>No chats yet. Start a new conversation to begin.</p>
              <button type="button" className="chat-btn" onClick={onNewChat}>
                + New chat
              </button>
            </div>
          ) : (
            <ul className="chat-list">
              {sessions.map((s) => (
                <li key={s.id} className="chat-list-item">
                  <button
                    type="button"
                    className="chat-entry"
                    onClick={() => openChat(s.id)}
                  >
                    <span className="chat-entry-title">{s.title}</span>
                    <span className="chat-entry-meta">
                      {s.messages.length} msg ·{" "}
                      {new Date(s.createdAt).toLocaleDateString()}
                    </span>
                  </button>
                  <button
                    type="button"
                    className="chat-entry-del"
                    onClick={(e) => {
                      e.stopPropagation();
                      onDeleteChat(s.id);
                    }}
                    title="Delete chat"
                    aria-label={`Delete chat ${s.title}`}
                  >
                    ×
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : (
        <>
          <div className="chat-log" ref={listRef}>
            {active && active.messages.length === 0 && (
              <div className="chat-empty">
                <p>
                  I can see your linked banks, balances and expenses. Ask me
                  anything about them — for example:
                </p>
                <ul className="chat-suggestions">
                  {SUGGESTED_PROMPTS.map((prompt) => (
                    <li key={prompt}>
                      <button
                        type="button"
                        className="chat-suggestion"
                        onClick={() => void sendMessage(prompt)}
                      >
                        “{prompt}”
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {active?.messages.map((m) => (
              <div key={m.id} className={`bubble ${m.role}`}>
                <p className="content">
                  {m.pending ? (
                    <span className="typing">thinking…</span>
                  ) : (
                    m.content
                  )}
                </p>
                {m.role === "assistant" && m.reasoning && !m.pending && (
                  <details
                    className="reasoning"
                    open={!!expanded[m.id]}
                    onToggle={(e) => {
                      const el = e.currentTarget as HTMLDetailsElement;
                      setExpanded((prev) => ({ ...prev, [m.id]: el.open }));
                      // Keep the newly-revealed reasoning inside the chat-log
                      // viewport so it doesn't slide off the bottom.
                      if (el.open) {
                        requestAnimationFrame(() => {
                          el.scrollIntoView({
                            block: "nearest",
                            behavior: "smooth",
                          });
                        });
                      }
                    }}
                  >
                    <summary>Reasoning</summary>
                    <pre>{m.reasoning}</pre>
                  </details>
                )}
              </div>
            ))}
          </div>

          <form className="prompt" onSubmit={onSubmit}>
            <span className="chev">›</span>
            <input
              aria-label="Ask the banking assistant"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="ask about your spending, limits, or balance"
            />
            <button className="run" type="submit">
              Run
            </button>
          </form>
        </>
      )}
    </section>
  );
}

/* Floating action button + slide-in right-side dock (Copilot-style).
   Wraps ChatPanel so all the session / reasoning / grounding logic is reused.
   The dock is resizable via a left-edge drag handle and pushes the main
   content aside (via a CSS variable) instead of overlaying it. */
const DOCK_WIDTH_STORAGE_KEY = "chat.dock.width";
const DOCK_DEFAULT_WIDTH = 420;
const DOCK_MIN_WIDTH = 320;
const DOCK_MAX_RATIO = 0.7; // never take more than 70% of the viewport
const PUSH_LAYOUT_MIN_VW = 900; // below this, the dock overlays instead of pushing

function clampDockWidth(w: number): number {
  const vw = typeof window !== "undefined" ? window.innerWidth : 1600;
  const max = Math.max(DOCK_MIN_WIDTH, Math.floor(vw * DOCK_MAX_RATIO));

  return Math.min(max, Math.max(DOCK_MIN_WIDTH, Math.round(w)));
}

function ChatDock({
  data,
  accountId,
}: {
  data: DashboardPayload;
  accountId: string;
}) {
  const [open, setOpen] = useState(false);
  const [width, setWidth] = useState<number>(() => {
    const raw =
      typeof window !== "undefined"
        ? localStorage.getItem(DOCK_WIDTH_STORAGE_KEY)
        : null;
    const parsed = raw ? Number(raw) : DOCK_DEFAULT_WIDTH;

    return clampDockWidth(
      Number.isFinite(parsed) ? parsed : DOCK_DEFAULT_WIDTH,
    );
  });
  const [dragging, setDragging] = useState(false);

  // Push the layout aside on wide viewports; the CSS variable is read by
  // `body.chat-pushed { padding-right: var(--dock-width) }`.
  useEffect(() => {
    const root = document.documentElement;
    root.style.setProperty("--dock-width", `${width}px`);
    const canPush = window.innerWidth >= PUSH_LAYOUT_MIN_VW;
    document.body.classList.toggle("chat-pushed", open && canPush);

    return () => {
      document.body.classList.remove("chat-pushed");
    };
  }, [open, width]);

  // Keep width valid across viewport resizes and disable the push layout
  // if the viewport shrinks below the threshold while the dock is open.
  useEffect(() => {
    const onResize = () => {
      setWidth((w) => clampDockWidth(w));
      document.body.classList.toggle(
        "chat-pushed",
        open && window.innerWidth >= PUSH_LAYOUT_MIN_VW,
      );
    };
    window.addEventListener("resize", onResize);

    return () => window.removeEventListener("resize", onResize);
  }, [open]);

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setOpen(false);
      }
    };
    window.addEventListener("keydown", onKey);

    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  // Drag-to-resize from the left edge of the dock.
  const onResizeStart = (e: ReactMouseEvent) => {
    e.preventDefault();
    setDragging(true);
    const onMove = (ev: MouseEvent) => {
      setWidth(clampDockWidth(window.innerWidth - ev.clientX));
    };
    const onUp = () => {
      setDragging(false);
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
      // Persist the final width.
      setWidth((w) => {
        try {
          localStorage.setItem(DOCK_WIDTH_STORAGE_KEY, String(w));
        } catch {
          /* storage disabled — ignore */
        }

        return w;
      });
    };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  };

  return (
    <>
      <button
        type="button"
        className={`chat-fab ${open ? "hidden" : ""}`}
        onClick={() => setOpen(true)}
        aria-label="Open assistant"
        title="Open assistant"
      >
        <svg
          viewBox="0 0 24 24"
          width="22"
          height="22"
          aria-hidden="true"
          focusable="false"
        >
          <path
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            d="M4 5h16v11H8l-4 4z"
          />
        </svg>
      </button>

      <aside
        className={`chat-dock ${open ? "open" : ""} ${dragging ? "dragging" : ""}`}
        aria-label="Assistant"
      >
        <div
          className="chat-resize"
          role="separator"
          aria-orientation="vertical"
          aria-label="Resize chat panel"
          title="Drag to resize"
          onMouseDown={onResizeStart}
        />
        {open && (
          <ChatPanel
            data={data}
            accountId={accountId}
            onClose={() => setOpen(false)}
          />
        )}
      </aside>
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
  const [addOpen, setAddOpen] = useState(false);

  const trendDelta =
    data.trend.length >= MIN_TREND_POINTS
      ? data.trend[data.trend.length - 1].balance - data.trend[0].balance
      : null;

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
          <p className="v">
            {(data.account.utilizationRate * 100).toFixed(1)}%
          </p>
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
  const [authed, setAuthed] = useState(
    () => sessionStorage.getItem("authed") === "1",
  );
  const [data, setData] = useState<DashboardPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const accountId = useMemo(resolveAccountId, []);

  useEffect(() => {
    if (!accountId) {
      setError(
        "Missing accountId. Set VITE_ACCOUNT_ID or use ?accountId=<uuid> in URL.",
      );
      setLoading(false);

      return;
    }

    // On return from the bank OAuth flow, let the callback effect own the fetch
    // so the two requests don't race.
    const params = new URLSearchParams(window.location.search);
    if (params.get("code") && params.get("state")) {
      return;
    }

    fetchDashboard(accountId)
      .then((payload) => {
        setData(payload);
        setError(null);
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
        setError(null);
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
            Connect your first bank to start aggregating balances, limits and
            spending. Add as many as you like — nothing is shown until there’s
            real data.
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

  return (
    <>
      <Dashboard data={data} accountId={accountId} onLogout={onLogout} />
      <ChatDock data={data} accountId={accountId} />
    </>
  );
}

export default App;
