import { readdir, readFile, writeFile, unlink, mkdir } from 'node:fs/promises'
import { join } from 'node:path'
import type { Project } from '../types'

const DATA_DIR = join(import.meta.dir, '../../data/projects')

async function ensureDir() {
  await mkdir(DATA_DIR, { recursive: true })
}

export async function listProjects(): Promise<Project[]> {
  await ensureDir()
  const files = await readdir(DATA_DIR)
  const projects: Project[] = []
  for (const file of files) {
    if (!file.endsWith('.json')) continue
    const raw = await readFile(join(DATA_DIR, file), 'utf-8')
    projects.push(migrateProject(JSON.parse(raw)))
  }
  return projects.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
}

export async function getProject(id: string): Promise<Project | null> {
  try {
    const raw = await readFile(join(DATA_DIR, `${id}.json`), 'utf-8')
    return migrateProject(JSON.parse(raw))
  } catch {
    return null
  }
}

/**
 * Migrate legacy data formats on load.
 */
function migrateProject(project: Project): Project {
  // Legacy specId → specIds
  if (project.expose) {
    const raw = project.expose as any
    if (raw.specId && !raw.specIds) {
      project.expose = {
        specIds: [raw.specId],
        port: raw.port ?? 8080,
        host: raw.host ?? '0.0.0.0',
      }
    } else if (!project.expose.specIds) {
      project.expose.specIds = []
    }
  }

  // Legacy respond fields: body["X"] → { mode: 'to', value: 'X' }
  for (const flow of project.flows) {
    for (const step of flow.steps) {
      if (step.type === 'respond' || step.type === 'map') {
        for (const f of step.fields) {
          if (!f.mode) {
            const bodyRef = f.value.match(/^(?:req\.)?body\["(.+)"\]$/)
            if (bodyRef) {
              f.value = bodyRef[1]
              f.mode = 'to'
            } else {
              f.mode = 'set'
            }
          }
        }
      }
    }
  }

  return project
}

export async function saveProject(project: Project): Promise<void> {
  await ensureDir()
  project.updatedAt = new Date().toISOString()
  await writeFile(join(DATA_DIR, `${project.id}.json`), JSON.stringify(project, null, 2))
}

export async function deleteProject(id: string): Promise<boolean> {
  try {
    await unlink(join(DATA_DIR, `${id}.json`))
    return true
  } catch {
    return false
  }
}
