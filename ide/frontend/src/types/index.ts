export interface Project {
  id: string
  name: string
  version: number
  description?: string
  specs: SpecFile[]
  expose?: ExposeConfig
  adapters: AdapterConfig[]
  flows: FlowConfig[]
  createdAt: string
  updatedAt: string
}

export interface SpecFile {
  id: string
  filename: string
  content: string
  parsed: ParsedSpec
}

export interface ParsedSpec {
  title: string
  operations: OperationDef[]
  schemas: SchemaDef[]
}

export interface OperationDef {
  operationId: string
  method: string
  path: string
  summary?: string
  requestSchema?: string
  responseSchema?: string
  parameters: ParameterDef[]
}

export interface ParameterDef {
  name: string
  in: 'path' | 'query' | 'header'
  required: boolean
  type: string
}

export interface SchemaDef {
  name: string
  fields: FieldDef[]
  required: string[]
}

export interface FieldDef {
  name: string
  type: string
  format?: string
  description?: string
}

export interface ExposeConfig {
  specIds: string[]
  port: number
  host: string
}

export interface AdapterConfig {
  id: string
  name: string
  specId?: string
  type: 'http' | 'postgres' | 'in-memory'
  baseUrl?: string
  postgres?: {
    url: string
    username: string
    password?: string
    table: string
    schema?: string
  }
}

export interface FlowConfig {
  operationId: string
  steps: StepConfig[]
  statusCode?: number
}

export type StepConfig =
  | ProcessStep
  | CallStep
  | LogStep
  | RespondStep
  | MapStep

export interface ProcessStep {
  type: 'process'
  name: string
  expression: string
}

export interface CallStep {
  type: 'call'
  adapterName: string
  method: string
  path: string
}

export interface LogStep {
  type: 'log'
  message: string
  level: 'DEBUG' | 'INFO' | 'WARN' | 'ERROR'
}

export interface RespondField {
  key: string
  value: string
  mode: 'to' | 'set'
}

export interface RespondStep {
  type: 'respond'
  fields: RespondField[]
}

export interface MapStep {
  type: 'map'
  fields: RespondField[]
}

// Database browser types
export interface TableInfo {
  name: string
  rowCount: number
}

export interface ColumnInfo {
  name: string
  type: string
  nullable: boolean
  isPrimaryKey: boolean
}

// Canvas selection state
export type Selection =
  | { type: 'flow'; operationId: string }
  | { type: 'step'; operationId: string; stepIndex: number }
  | { type: 'connector'; adapterId: string }
  | { type: 'spec'; specId: string }
  | null
