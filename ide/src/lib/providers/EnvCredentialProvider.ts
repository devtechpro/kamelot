import type { ICredentialProvider } from '../interfaces/ICredentialProvider'
import { CredentialNotFoundError } from '../interfaces/ICredentialProvider'

/**
 * Reads credentials from process.env (populated by Next.js from .env.local).
 * For production use, replace with VaultCredentialProvider or similar.
 */
export class EnvCredentialProvider implements ICredentialProvider {
  async get(name: string): Promise<string> {
    const value = process.env[name]
    if (!value || value.trim() === '') {
      throw new CredentialNotFoundError(name)
    }
    return value
  }
}
