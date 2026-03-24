# Kamelot Author

**AI-powered integration authoring tool for Apache Camel.**

Kamelot Author is the first increment of the Kamelot Platform. It lets Requirement Engineers, Analysts, and Developers describe an integration in plain language — or upload a PlantUML diagram — and instantly receive a generated Apache Camel YAML route, a visual flow diagram, a sequence diagram, and live test results, all in one screen.

---

## What it does

1. **Describe** your integration in natural language (or upload a `.puml` file)
2. **Kamelot Author asks** targeted clarifying questions to gather all requirements
3. **Generates** a production-ready Apache Camel YAML DSL route (Camel JBang 4.9.0 compatible)
4. **Visualises** the route as an interactive flow diagram (React Flow) and a sequence diagram (PlantUML via Kroki)
5. **Executes** the route live via Camel JBang and streams real results into the UI

---

## Tech stack

| Layer | Technology |
|---|---|
| Framework | Next.js 14, TypeScript, Tailwind CSS |
| LLM | Claude Sonnet (via Claude Code CLI or Anthropic API) |
| Code editor | Monaco Editor — YAML, read-only |
| Flow diagram | React Flow (xyflow) — auto-generated from parsed YAML |
| Sequence diagram | PlantUML rendered by Kroki |
| Route execution | Apache Camel JBang 4.9.0 |

---

## Prerequisites

Install these before running the project:

| Requirement | Version | Install |
|---|---|---|
| Node.js | 20+ | https://nodejs.org |
| Java JDK | 17+ | https://adoptium.net |
| JBang | latest | `curl -Ls https://sh.jbang.dev \| bash -s - app setup` |
| Camel JBang | 4.9.0 | `jbang org.apache.camel:camel-jbang@4.9.0 --help` (run once to pre-download) |

---

## Setup

### 1. Clone and install

```bash
git clone <repo-url>
cd ide
npm install
```

### 2. Configure credentials

Create a `.env.local` file in the project root (never commit this file):

```

# Optional — only needed if you are NOT using Claude Code CLI
# ANTHROPIC_API_KEY=sk-ant-...

# Optional — override with a self-hosted Kroki instance
KROKI_URL=https://kroki.io
```

### 3. LLM authentication — two options

**Option A — Anthropic API key (recommended)**

Add `ANTHROPIC_API_KEY=sk-ant-...` to your `.env.local`. Get a key at https://console.anthropic.com.

This is the most reliable path — direct API call, consistent latency, strict JSON enforcement.

**Option B — Claude Code CLI (zero-config fallback)**

If you have [Claude Code](https://claude.ai/code) installed and authenticated, no API key is needed. Kamelot Author detects the CLI automatically and uses it as a fallback when no API key is set.

```bash
# Authenticate (one-time, opens browser)
claude login
```

Note: the CLI path adds subprocess startup overhead and is slower for complex route generation. Set `ANTHROPIC_API_KEY` for the best experience.

Kamelot Author checks for an API key first. The CLI is only used when no API key is configured.

### 4. Run

```bash
npm run dev
```

Open [http://localhost:3000](http://localhost:3000).

---

## Demo walkthrough

Try this prompt to see the full end-to-end flow:

> *"Get all international flights currently arriving at Dubai International Airport and show flight number, airline, origin city, and estimated arrival time."*

**What happens:**
1. Kamelot Author asks a few clarifying questions (trigger type, time window, error handling)
2. Once requirements are confirmed, it generates a Camel YAML route and a PlantUML sequence diagram simultaneously
3. The route appears in the Monaco editor (YAML tab), the flow diagram renders on the canvas (Flow tab), and the sequence diagram loads (Sequence tab)
4. Click **Test Route** — Camel JBang executes the route and real flight data from AviationStack appears in the terminal

---

## Layout overview

```
┌─────────────────────────────────────────────────────┐
│  Kamelot Author                        [dark header] │
├──────────────┬──────────────────────────────────────┤
│              │  [Flow] [YAML] [Sequence] [XML] ...   │
│  File tree   │                                      │
│              │  React Flow canvas / Monaco editor /  │
│              │  PlantUML sequence diagram            │
├──────────────┴──────────────────────────────────────┤
│  [Prompt] [Deploy] [Test]                            │
│  Chat terminal — describe integration, answer        │
│  clarifying questions, see test output               │
└─────────────────────────────────────────────────────┘
```

The terminal has two modes:
- **Simple** — chat bubble UI
- **Pro** — monospace terminal output

---

## Project structure

```
src/
├── app/
│   ├── page.tsx                    ← landing / project list
│   ├── project/[id]/page.tsx       ← main IDE layout
│   └── api/
│       ├── generate/route.ts       ← POST: prompt → YAML + PlantUML
│       ├── execute/route.ts        ← POST: YAML → JBang → output
│       └── validate/route.ts       ← POST: YAML → Camel MCP validation
├── components/
│   ├── Terminal.tsx                ← chat input + conversation history
│   ├── YamlEditor.tsx              ← Monaco editor
│   ├── RouteCanvas.tsx             ← React Flow diagram
│   ├── SequenceDiagram.tsx         ← Kroki PlantUML render
│   └── TestPanel.tsx               ← execution output
└── lib/
    ├── interfaces/                 ← ILLMProvider, IRouteExecutor, etc.
    ├── providers/
    │   ├── AnthropicProvider.ts    ← direct Anthropic API
    │   ├── ClaudeCodeCLIProvider.ts← Claude Code CLI (auto-detected)
    │   ├── JBangExecutor.ts        ← Camel JBang process management
    │   └── KrokiRenderer.ts        ← PlantUML → SVG via Kroki
    ├── utils/
    │   ├── detectClaudeCLI.ts      ← detects claude CLI on PATH
    │   └── parseLLMResponse.ts     ← shared LLM response parser
    ├── camel-parser.ts             ← YAML → React Flow nodes + edges
    └── llm.ts                      ← system prompts + provider factory
```

---

## Credentials and security

- Credentials are **never** passed through the UI or exposed to the browser
- All credential reads happen server-side via the `ICredentialProvider` interface
- `.env.local` is gitignored — never commit it
- The LLM provider is selected server-side; the frontend never knows which one is in use

---

## Troubleshooting

**"No LLM provider available"**
→ Either run `claude login` to authenticate Claude Code CLI, or add `ANTHROPIC_API_KEY` to `.env.local`.

**"JBang not installed"**
→ Install JBang: `curl -Ls https://sh.jbang.dev | bash -s - app setup`, then open a new terminal.

**Sequence diagram not rendering**
→ Check your internet connection — diagrams are rendered via the public Kroki API at `kroki.io`. To use a self-hosted instance, set `KROKI_URL` in `.env.local`.

**Route execution returns no data**
→ Verify your `AVIATIONSTACK_API_KEY` is set in `.env.local` and is valid (test at `https://api.aviationstack.com/v1/flights?access_key=YOUR_KEY&arr_iata=DXB`).
