# Kamelot Author — Claude Code Build Instructions

## What This Is

Kamelot Author (also called here Kamelot or the system) is the first increment of the Kamelot Platform — an AI-powered integration authoring tool. It allows Requirement Engineers, Analysts, and Developers to describe integrations in plain language (or upload a PlantUML diagram), receive a generated Apache Camel YAML route, visualise it as a flow diagram including relevant connectors (like in MuleSoft, Workato, Boomi and others), and a sequence diagram for Analysts and Requirement Engineers and test it live — all in one screen.

This is a customer demo build. It must work reliably, look polished, and complete the full demo scenario end-to-end. Prioritise correctness and visual quality over feature breadth.

---

## Demo Scenario (build exactly this)

**The user types:** "Create an tntegration to get all international flights arriving at Dubai International Airport at a requested time window and show flight number, airline, origin city, and estimated arrival time."

**Expected flow:**
1. User enters the prompt in the input area
2. Kamelot Author analyzes and requests furhter required input if necessary
3. Once Kamelot Author has enough input, it calls Claude Sonnet to generate a Camel YAML route that calls AviationStack API with `arr_iata=DXB`
4. System simultaneously generates a PlantUML sequence diagram representing the data flow
5. YAML route is shown in Monaco editor (read-only, syntax highlighted)
6. React Flow canvas shows the route as a visual flow diagram (auto-generated from parsed YAML)
7. PlantUML sequence diagram is rendered inline via Kroki
8. User can now switch between views: Developer view, including the flow diagram with connectors and the Analyst view including the sequence diagram.
9. User clicks "Test Route"
10. Backend spawns Camel JBang to execute the route
11. Real flight data from AviationStack appears in the result panel inline

This entire flow must work end-to-end for the demo.

---

## Tech Stack — Do Not Deviate

- **Framework:** Next.js 14 with App Router, TypeScript
- **Visual canvas:** React Flow (xyflow) — read-only, auto-generated from parsed YAML
- **Code editor:** Monaco Editor (`@monaco-editor/react`) — YAML mode, read-only
- **PlantUML rendering:** Kroki public API (`https://kroki.io/plantuml/svg/` + base64-encoded diagram)
- **LLM:** Anthropic Claude Sonnet via `@anthropic-ai/sdk` — generates both YAML and PlantUML in one call
- **Route validation:** Camel MCP Server (called from backend before execution)
- **Route execution:** Camel JBang — spawned as persistent background process via Node.js `child_process`, not a new process per request
- **Styling:** Tailwind CSS
- **Backend:** Next.js API routes (no separate Express server needed)

---

## Architecture

All backend logic lives in Next.js API routes under `src/app/api/`:

```
src/
├── app/
│   ├── page.tsx                    ← single-page UI layout
│   ├── layout.tsx
│   └── api/
│       ├── generate/route.ts       ← POST: takes prompt or PlantUML text → returns YAML + PlantUML
│       ├── execute/route.ts        ← POST: takes YAML → runs via JBang → returns output
│       └── validate/route.ts       ← POST: takes YAML → validates via Camel MCP → returns result
├── components/
│   ├── PromptInput.tsx             ← text input + file upload button
│   ├── YamlEditor.tsx              ← Monaco editor, read-only YAML display
│   ├── RouteCanvas.tsx             ← React Flow diagram auto-generated from YAML
│   ├── SequenceDiagram.tsx         ← Kroki PlantUML render
│   └── TestPanel.tsx               ← execution output, streaming if possible
└── lib/
    ├── llm.ts                      ← Anthropic SDK, prompt templates
    ├── jbang.ts                    ← JBang process management (persistent warm instance)
    ├── camel-parser.ts             ← parses Camel YAML → React Flow nodes + edges
    └── kroki.ts                    ← encodes PlantUML → Kroki URL
```

---

## UI Layout

Single page, dark header, clean white content area. Three-panel layout:

```
┌─────────────────────────────────────────────────────┐
│  Kamelot Author          [dark header, logo left]   │
├──────────────┬──────────────────────────────────────┤
│              │                                      │
│  LEFT PANEL  │           RIGHT PANEL                │
│              │                                      │
│  Files       │  [Tab: Flow] [Tab: YAML] [Tab: Seq]  │
│              │                                      │
│              │  React Flow canvas  /                │
│              │  Monaco editor      / Kroki diagram  │
│              │  (tab-switched:                      │
│              │                - Developer view      │
│              │                - Analyst view )      │
│──────────────────────────────────────---------------|
│                BOTTOM PANEL                         │
│                (tab switched:                       |
|                 - Prompt Input [Generate],[clear]   |
|                 - Deploy                            |
|                 - TEST --> OUTPUT (bottom panel)    |
|                 [Test Route button]                 │
│                output streamed here)                │
└────────────────────────────────────────────────────–┘
```

The right panel has three tabs: Flow (React Flow canvas), YAML (Monaco), Sequence (PlantUML via Kroki). All three populate simultaneously after generation.

---

## LLM Prompt Design

The `/api/generate` endpoint sends a single prompt to Claude Sonnet that produces both outputs in one response. Structure the response as JSON:

```json
{
  "yaml": "<the complete Camel YAML route>",
  "plantuml": "<the complete PlantUML @startuml...@enduml sequence diagram>"
}
```

For the example prompt, the system prompt must instruct Claude to:
1. Generate a valid Apache Camel YAML DSL route (not Java, not XML)
2. Use the `camel-http` or `camel-rest` component to call the AviationStack API
3. Include proper error handling (onException block)
4. Generate a PlantUML sequence diagram that accurately represents the data flow
5. Return ONLY valid JSON — no markdown fences, no extra text

For other prompts, Kamelot Author must use the required Camel components to fullfill the users needs.

Example Camel YAML structure to target:
```yaml
- route:
    id: aviationstack-arrivals
    from:
      uri: timer:trigger
      parameters:
        repeatCount: "1"
    steps:
      - to:
          uri: https://api.aviationstack.com/v1/flights
          parameters:
            access_key: "{{aviationstack.api.key}}"
            arr_iata: DXB
            flight_status: active
      - unmarshal:
          json: {}
      - to:
          uri: log:result
          parameters:
            showBody: "true"
```

---

## YAML → React Flow Parser

`camel-parser.ts` must parse the Camel YAML and produce React Flow nodes and edges. Rules:

- Each `from` becomes a green source node (leftmost)
- Each `to` or `log` becomes a blue processing node
- Each `onException` becomes a red error node (positioned below the main flow)
- Nodes are laid out left-to-right with 200px horizontal spacing
- Edge labels show the component name (e.g. "https", "log")
- Node labels show the URI stripped of parameters (e.g. "AviationStack API")

---

## JBang Execution

`jbang.ts` manages execution. Key requirements:

- On backend startup, pre-warm a JBang process so first execution is fast
- Write generated YAML to a temp file in `/tmp/kamelot/`
- Execute: `jbang org.apache.camel:camel-jbang@4.9.0 run <file>.yaml --max-seconds=15`
- Capture stdout and stderr
- Return both to the frontend as the test result
- The AviationStack API key must be injected as a JVM property: `-Daviationstack.api.key=$AVIATIONSTACK_API_KEY`
- Timeout after 20 seconds — return partial output if available

---

## Credential Management

**Principle:** Credentials are never passed through the UI layer. They are configured once, server-side, and injected at the point of use.

**Architecture:** All credential access goes through `ICredentialProvider` (defined in `src/lib/interfaces/ICredentialProvider.ts`). The current implementation is `EnvCredentialProvider` which reads from `process.env`. Future implementations can swap in HashiCorp Vault, AWS Secrets Manager, or an OS keychain provider without touching any other code.

**Local setup:** Create `.env.local` in the project root (gitignored, never committed):

```
ANTHROPIC_API_KEY=sk-ant-...
AVIATIONSTACK_API_KEY=...
KROKI_URL=https://kroki.io    # override with self-hosted if needed
```

**Security rules:**
- Never use `export KEY=value` in the shell — this exposes credentials to any subprocess
- Never pass credentials from the frontend to the backend — all credential reads happen server-side
- The UI shows a clear error if a required credential is missing (guides user to `.env.local`)
- Never log credential values, even partially

---

## PlantUML Input (Upload)

The `PromptInput` component includes a file upload button that accepts `.puml` and `.txt` files. When a file is uploaded:
- Read file contents as text
- Display a badge showing "PlantUML uploaded"
- On Generate, send the PlantUML text as the input (not the freetext prompt)
- The system prompt for PlantUML input differs: instruct Claude to read the sequence diagram and generate the corresponding Camel route

---

## Build Order for Claude Code

Build in this exact sequence to enable testing at each step:

1. Next.js project scaffold with Tailwind and TypeScript
2. Basic three-panel UI layout (static, no logic)
3. `/api/generate` endpoint — LLM call returning YAML + PlantUML JSON
4. Prompt input component wired to generate endpoint
5. Monaco editor displaying generated YAML
6. `camel-parser.ts` + React Flow canvas displaying parsed route
7. Kroki rendering of PlantUML sequence diagram (SequenceDiagram component)
8. `/api/execute` endpoint — JBang execution + output capture
9. TestPanel component wired to execute endpoint
10. PlantUML file upload in PromptInput
11. Polish: loading states, error handling, empty states, visual styling

Do not skip steps or reorder them. Each step must work before proceeding.

---

## Quality Requirements

- TypeScript strict mode — no `any` types
- All API routes must return proper HTTP status codes and error messages
- Loading states on all async operations (spinner or skeleton)
- The demo must not crash if JBang is not installed — show a clear "JBang not installed" message in the test panel instead
- Mobile layout is not required — optimise for 1440px desktop
- No authentication required for the demo

---

## Engineering Principles — Non-Negotiable

### SOLID Principles
Every class, module, and component must follow SOLID strictly:

**Single Responsibility:** Each file does one thing. `llm.ts` only handles LLM calls. `jbang.ts` only handles process management. `camel-parser.ts` only parses YAML to graph data. No file mixes concerns.

**Open/Closed:** Logic must be extendable without modifying existing code. LLM providers, execution runtimes, and diagram renderers must be pluggable via interfaces — adding a new LLM or a new runtime means adding a new implementation, not editing existing code.

**Liskov Substitution:** Where interfaces are defined (e.g. `IRouteExecutor`, `IDiagramRenderer`, `ILLMProvider`), all implementations must be fully interchangeable.

**Interface Segregation:** Define narrow, focused interfaces. A `IRouteExecutor` only exposes `execute(yaml: string): Promise<ExecutionResult>` — not logging, not validation, not anything else.

**Dependency Inversion:** API routes depend on abstractions, not concrete implementations. Inject `ILLMProvider`, `IRouteExecutor`, `IDiagramRenderer` — never instantiate them directly in route handlers.

Example structure:
```
src/lib/
├── interfaces/
│   ├── ILLMProvider.ts
│   ├── IRouteExecutor.ts
│   └── IDiagramRenderer.ts
├── providers/
│   ├── AnthropicProvider.ts      ← implements ILLMProvider
│   ├── JBangExecutor.ts          ← implements IRouteExecutor
│   └── KrokiRenderer.ts          ← implements IDiagramRenderer
```

---

### UX and Design Standards

This is a product demo for an enterprise customer. The UI must look and feel like a premium, modern developer tool — not a tutorial project.

**Visual design:**
- Dark header bar with the Kamelot Author logo/wordmark
- Clean white/light grey content area with generous padding
- Use a professional colour palette — deep navy or slate primary, with amber as the accent colour (consistent with the Kamelot brand)
- Rounded corners (8–12px) on all cards and panels
- Subtle drop shadows on elevated elements
- Smooth CSS transitions (150–200ms) on all interactive state changes

**Typography:**
- Inter or Geist font (Next.js default Geist is fine)
- Clear size hierarchy: page title 24px bold, section labels 12px uppercase tracked, body 14px
- Never use pure black text — use slate-800 or slate-900

**Interaction design:**
- Every async action must show a loading state immediately — spinner or skeleton, never a frozen UI
- Success and error states must be visually distinct and clear — never silent failures
- The Generate button must be disabled while generation is in progress
- The Test button must be disabled while execution is in progress and while no YAML is present
- Smooth appear animation (fade + slight upward translate) when results populate

**Layout and spacing:**
- Consistent 16px or 24px spacing unit throughout
- Content must never touch the edge of its container — minimum 16px padding on all panels
- The three right-panel tabs (Flow, YAML, Sequence) must be clearly active/inactive — not ambiguous

**Empty states:**
- Before generation: show a helpful placeholder in each panel — e.g. "Describe your integration above to generate a route", not a blank white box
- The test panel must show "Run your route to see results" before the first test

**Responsiveness:**
- Optimised for 1440px desktop as primary
- Must not break visually at 1280px

---

## What NOT to Build in This Increment

- Drag-and-drop editing of the route canvas (read-only only)
- Route saving / persistence / history
- Multi-route projects
- Deployment to Kubernetes or Camel K
- User accounts or authentication
- Governance or lifecycle features

---

## Living Document — Keep This File Updated

This CLAUDE.md is updated at the end of every phase. As requirements are refined, architecture decisions are made, or scope changes, update the relevant section here before proceeding. Claude Code reads this file at the start of every session — it must always reflect the current agreed state of the project, not the initial state.

---

## Prerequisites (must be installed on the host machine before running)

- Node.js 20+
- Java JDK 17+ (for JBang)
- JBang: `curl -Ls https://sh.jbang.dev | bash -s - app setup`
- Camel JBang: `jbang org.apache.camel:camel-jbang@4.9.0 --help` (pre-downloads on first run)

## API Keys — Use .env.local (never commit it)

Create a `.env.local` file in the project root with your API keys. Next.js reads this file automatically and it is **never** a shell environment variable, so it cannot be read by Claude Code Bash tool calls.

```
# .env.local  ← create this file yourself, never paste contents into chat
ANTHROPIC_API_KEY=sk-ant-...
AVIATIONSTACK_API_KEY=...
KROKI_URL=https://kroki.io
```

The `.gitignore` already excludes `.env*` so this file will never be committed. Do NOT use `export` in your shell for these keys — that exposes them to any subprocess that inherits your environment.
