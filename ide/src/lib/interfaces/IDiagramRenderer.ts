export interface IDiagramRenderer {
  render(source: string): Promise<string>
}
