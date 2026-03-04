# IDE Frontend

Vue 3 SPA for the Studio web UI.

## Running

```bash
cd ide/frontend
bun install
bun run dev     # dev server on port 5173
```

## Stack

- Vue 3 + TypeScript
- Pinia (state management)
- Splitpanes (resizable panels)
- Monaco Editor (code editing)
- Vite (build)

## Components

| Area | Components |
|------|------------|
| Layout | `IdeLayout`, `Toolbar`, `CenterPanel` |
| Project tree | `ProjectTree`, `NewProjectDialog`, `SpecUploadDialog`, `ConnectorDialog` |
| Canvas | `FlowCanvas`, `FlowLane`, `FlowNode`, `StepBlock`, `StepPalette`, `SpecViewer`, `DatabaseBrowser` |
| Properties | `PropertiesPanel`, `FlowProperties`, `StepProperties`, `ConnectorProperties`, `StepPicker` |
| Mapping | `MappingPanel` |
| Runtime | `RuntimePanel` |
| Settings | `ProjectSettings` |
| Source | `SourceView` |
