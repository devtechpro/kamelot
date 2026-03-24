export interface ExecutionResult {
  stdout: string
  stderr: string
  exitCode: number
  timedOut: boolean
}

export interface IRouteExecutor {
  execute(yaml: string): Promise<ExecutionResult>
}
