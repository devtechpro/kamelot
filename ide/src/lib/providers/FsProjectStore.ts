import fs from 'fs/promises'
import path from 'path'
import os from 'os'
import crypto from 'crypto'
import type { IProjectStore, Project } from '../interfaces/IProjectStore'
import { ProjectNotFoundError } from '../interfaces/IProjectStore'

const KAMELOT_DIR = path.join(os.homedir(), '.kamelot')
const PROJECTS_JSON = path.join(KAMELOT_DIR, 'projects.json')

function projectDir(id: string): string {
  return path.join(KAMELOT_DIR, 'projects', id)
}

async function readIndex(): Promise<Project[]> {
  try {
    const raw = await fs.readFile(PROJECTS_JSON, 'utf-8')
    return JSON.parse(raw) as Project[]
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code === 'ENOENT') return []
    throw err
  }
}

async function writeIndex(projects: Project[]): Promise<void> {
  await fs.mkdir(KAMELOT_DIR, { recursive: true })
  await fs.writeFile(PROJECTS_JSON, JSON.stringify(projects, null, 2), 'utf-8')
}

export class FsProjectStore implements IProjectStore {
  async list(): Promise<Project[]> {
    return readIndex()
  }

  async get(id: string): Promise<Project> {
    const projects = await readIndex()
    const project = projects.find((p) => p.id === id)
    if (!project) throw new ProjectNotFoundError(id)
    return project
  }

  async create(name: string, folder: string): Promise<Project> {
    const id = crypto.randomUUID()
    const resolvedFolder = folder.trim() || path.join(KAMELOT_DIR, 'projects', id)
    const project: Project = {
      id,
      name,
      folder: resolvedFolder,
      createdAt: new Date().toISOString(),
    }
    const projects = await readIndex()
    projects.push(project)
    await writeIndex(projects)
    await fs.mkdir(resolvedFolder, { recursive: true })
    return project
  }

  async delete(id: string): Promise<void> {
    const projects = await readIndex()
    const idx = projects.findIndex((p) => p.id === id)
    if (idx === -1) throw new ProjectNotFoundError(id)
    const project = projects[idx]
    projects.splice(idx, 1)
    await writeIndex(projects)
    // Only delete the folder if it is an internally managed path
    const internalPath = projectDir(id)
    if (project.folder === internalPath) {
      await fs.rm(internalPath, { recursive: true, force: true })
    }
  }

  async saveFile(projectId: string, filename: string, content: string): Promise<void> {
    const projects = await readIndex()
    const project = projects.find((p) => p.id === projectId)
    const dir = project?.folder ?? projectDir(projectId)
    await fs.mkdir(dir, { recursive: true })
    await fs.writeFile(path.join(dir, filename), content, 'utf-8')
  }

  async readFile(projectId: string, filename: string): Promise<string> {
    const projects = await readIndex()
    const project = projects.find((p) => p.id === projectId)
    const dir = project?.folder ?? projectDir(projectId)
    return fs.readFile(path.join(dir, filename), 'utf-8')
  }

  async deleteFile(projectId: string, filename: string): Promise<void> {
    const projects = await readIndex()
    const project = projects.find((p) => p.id === projectId)
    const dir = project?.folder ?? projectDir(projectId)
    await fs.unlink(path.join(dir, filename))
  }
}
