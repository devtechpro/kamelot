/**
 * Provides named credentials to execution providers.
 * Implementations may source credentials from environment variables,
 * OS keychain, HashiCorp Vault, AWS Secrets Manager, or OAuth flows.
 *
 * Credentials are never returned to the UI layer — they are injected
 * server-side only, at the point of use.
 */
export interface ICredentialProvider {
  /**
   * Returns the value for a named credential.
   * @throws {CredentialNotFoundError} if the credential is not configured
   */
  get(name: string): Promise<string>
}

export class CredentialNotFoundError extends Error {
  constructor(public readonly credentialName: string) {
    super(
      `Credential "${credentialName}" is not configured. ` +
        `Add it to your .env.local file: ${credentialName}=<value>`
    )
    this.name = 'CredentialNotFoundError'
  }
}
