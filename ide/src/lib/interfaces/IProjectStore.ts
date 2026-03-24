export interface Project {
  id: string
  name: string
  folder: string
  createdAt: string
}

export interface IProjectStore {
  list(): Promise<Project[]>
  get(id: string): Promise<Project>
  create(name: string, folder: string): Promise<Project>
  delete(id: string): Promise<void>
  saveFile(projectId: string, filename: string, content: string): Promise<void>
  readFile(projectId: string, filename: string): Promise<string>
  deleteFile(projectId: string, filename: string): Promise<void>
}

export class ProjectNotFoundError extends Error {
  constructor(id: string) {
    super(`Project not found: ${id}`)
    this.name = 'ProjectNotFoundError'
  }
}
