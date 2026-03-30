# Changelog

## 2026-03-25 — Manual Flow Editing

### New Files

- **`src/lib/interfaces/INodeData.ts`** — Canonical `NodeData` interface and `StepKind` union type shared across the parser, serializer, canvas, and properties panel. Adds `stepKind`, `rawStep`, `stepIndex`, `routeId`, and `isException` fields to the existing display fields.

- **`src/lib/camel-serializer.ts`** — Inverse of `camel-parser.ts`. Converts React Flow nodes back to a valid Camel YAML string. Partitions nodes into `from` / main chain / exception nodes, sorts by `stepIndex`, emits `{ [stepKind]: rawStep }` per step, and assembles via `js-yaml`.

- **`src/lib/camel-component-registry.ts`** — Catalog of ~60 Apache Camel components across 10 categories (Trigger, HTTP, File & IO, Messaging, Cloud, Database, Data Format, Transformation, Routing, Error Handling). Each entry declares `scheme`, `label`, `description`, `category`, `stepKind`, typed `params[]`, `defaultRawStep`, and optional `defaultUri`. Extensible by adding entries — no code changes needed.

- **`src/components/ComponentPalette.tsx`** — Right-sidebar component palette. Real-time search filter, collapsible category groups, draggable chips. Each chip sets `dataTransfer` with `application/camel-component` on drag start.

- **`src/components/NodePropertiesPanel.tsx`** — Editable properties panel for the selected canvas node. Shows URI field for endpoint nodes, registry-driven typed parameter fields (string / password / boolean / integer / expression), and a generic key-value editor for unknown components. Includes a trash button (disabled for the `from` node). All changes propagate back to the canvas via `onChange(nodeId, updatedNodeData)`.

### Modified Files

- **`src/lib/camel-parser.ts`** — Additive changes only. All parsed nodes now carry `stepKind`, `rawStep`, `stepIndex`, `routeId` (on the `from` node), and `isException` (on `onException`/`doCatch` nodes). No existing fields were removed or renamed — `NodeDetailDrawer` and all read-only consumers continue to work without changes.

- **`src/components/RouteCanvas.tsx`** — Rewritten to support an optional editable mode. When `onYamlChange` is provided the canvas switches to controlled state (`editNodes`/`editEdges`) seeded from the YAML prop. New interactions:
  - **Drag to reorder** — nodes sorted by x-position on drag stop; `stepIndex` renumbered; edges rebuilt; YAML emitted.
  - **Delete key** — `Delete`/`Backspace` removes the selected node (skips the `from` node); edges reconnected; YAML emitted.
  - **Palette drop** — `onDrop` reads `application/camel-component`, calculates insertion index via `screenToFlowPosition`, inserts node, rebuilds edges, selects new node, emits YAML.
  - **Drag-over feedback** — amber ring and "Drop to add component" overlay during active drag.
  - When `onYamlChange` is absent the canvas is fully read-only, identical to the previous behaviour.
  - Restructured to wrap `FlowCanvasInner` in `ReactFlowProvider` so `useReactFlow()` (needed for `screenToFlowPosition`) works correctly.

- **`src/components/NodeDetailDrawer.tsx`** — Replaced local `NodeData` interface definition with an import from `INodeData.ts` to use the canonical type.

- **`src/app/project/[id]/page.tsx`** — Right panel gains a fixed-width sidebar (shown only when the Flow tab is active and YAML exists). Sidebar renders `NodePropertiesPanel` when a node is selected, otherwise `ComponentPalette`. New handlers:
  - `handleCanvasYamlChange` — keeps `generatedYaml` and the `route.yaml` file entry in sync.
  - `handleNodeSelect` — stores the selected node in `selectedCanvasNode` state.
  - `handleNodeDataChange` — parse → patch node data → serialize → emit (property panel edits round-trip through YAML).
  - `handleNodeDelete` — parse → filter → renumber → serialize → clear selection.
  - `RouteCanvas` receives `onYamlChange` and `onNodeSelect` only when a flow tab is active.

### Architecture Notes

- `generatedYaml` remains the single source of truth. Every editing path (AI generation, YAML editor, canvas drag/drop/delete, property edits) converges on `setGeneratedYaml`. No new state stores were introduced.
- `doTry` inner steps are flattened for display and the wrapper is not reconstructed on re-serialization. This is a known limitation — use the YAML editor for complex control flow with `doTry`.
