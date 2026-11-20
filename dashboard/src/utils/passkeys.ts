export function fromBase64Url(base64Url: string): ArrayBuffer {
  const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/')
  const pad = base64.length % 4 === 0 ? '' : '='.repeat(4 - (base64.length % 4))
  const binary = atob(base64 + pad)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes.buffer
}

function toBase64Url(buffer: ArrayBuffer | null | undefined): string {
  if (!buffer) return ''
  const bytes = new Uint8Array(buffer)
  let binary = ''
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i])
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
}

export function serializeCredential(credential: Credential | null): Record<string, unknown> {
  if (!credential) {
    throw new Error('Missing WebAuthn credential')
  }
  const pubKeyCred = credential as PublicKeyCredential
  const response = pubKeyCred.response as AuthenticatorAttestationResponse &
    AuthenticatorAssertionResponse & {
      getTransports?: () => string[]
    }

  const serializedResponse: Record<string, unknown> = {
    clientDataJSON: toBase64Url(response.clientDataJSON),
  }

  if (response.attestationObject) {
    serializedResponse.attestationObject = toBase64Url(response.attestationObject)
  }
  if (response.authenticatorData) {
    serializedResponse.authenticatorData = toBase64Url(response.authenticatorData)
  }
  if (response.signature) {
    serializedResponse.signature = toBase64Url(response.signature)
  }
  if (response.userHandle) {
    serializedResponse.userHandle = toBase64Url(response.userHandle)
  }
  if (typeof response.getTransports === 'function') {
    try {
      serializedResponse.transports = response.getTransports()
    } catch {
      // Ignore unsupported getTransports
    }
  }

  return {
    id: pubKeyCred.id,
    rawId: toBase64Url(pubKeyCred.rawId),
    type: pubKeyCred.type,
    authenticatorAttachment: pubKeyCred.authenticatorAttachment ?? null,
    response: serializedResponse,
    clientExtensionResults:
      typeof pubKeyCred.getClientExtensionResults === 'function'
        ? pubKeyCred.getClientExtensionResults()
        : {},
  }
}

export function getDefaultPasskeyName(): string {
  if (typeof navigator === 'undefined') return 'Browser Passkey'
  const ua = navigator.userAgent || ''
  if (/iPhone|iPad/i.test(ua)) return 'Apple iOS Passkey'
  if (/Macintosh|Mac OS X/i.test(ua)) return 'macOS Passkey'
  if (/Android/i.test(ua)) return 'Android Passkey'
  if (/Windows/i.test(ua)) return 'Windows Hello'
  if (/Linux/i.test(ua)) return 'Linux Passkey'
  return 'Browser Passkey'
}
