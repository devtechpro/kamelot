export type StepKind =
  | 'from'
  | 'to'
  | 'toD'
  | 'log'
  | 'marshal'
  | 'unmarshal'
  | 'transform'
  | 'setBody'
  | 'setHeader'
  | 'removeHeaders'
  | 'filter'
  | 'choice'
  | 'doTry'
  | 'onException'
  | 'doCatch'

export interface NodeData {
  // Display fields (existing — backward compatible)
  label: string
  params: Record<string, string>
  uri: string
  component: string

  // Serialization fields (new)
  stepKind: StepKind
  /** Exact parsed JS object under the step key — serialized verbatim by camel-serializer */
  rawStep: Record<string, unknown>
  /** Position in the main flow chain (0 = from node). Used for reorder sorting. */
  stepIndex: number
  /** Route id, only present on the from node */
  routeId?: string
  /** True for onException/doCatch nodes — excluded from main chain ordering */
  isException?: boolean
}
