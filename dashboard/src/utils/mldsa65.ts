import { ml_dsa65 } from '@noble/post-quantum/ml-dsa.js'
import { decodeURLSafe, encodeURLSafe } from '@stablelib/base64'

export interface Mldsa65KeyPair {
  seed: string
  verify: string
}

export interface ValidationResult {
  ok: boolean
  bytes?: Uint8Array
}

function normalizeUrlSafeBase64(value: string): string {
  return value.trim().replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
}

function decodeBase64UrlBytes(value: string): Uint8Array | null {
  try {
    const normalized = normalizeUrlSafeBase64(value)
    if (!normalized) return null
    const padLen = (4 - (normalized.length % 4)) % 4
    return decodeURLSafe(normalized + '='.repeat(padLen))
  } catch {
    return null
  }
}

export async function generateMldsa65(): Promise<Mldsa65KeyPair> {
  const seed = new Uint8Array(32)
  crypto.getRandomValues(seed)
  const keys = ml_dsa65.keygen(seed)
  return {
    seed: encodeURLSafe(seed).replace(/=/g, ''),
    verify: encodeURLSafe(keys.publicKey).replace(/=/g, ''),
  }
}

export function validateMldsa65Seed(value: string): ValidationResult {
  const bytes = decodeBase64UrlBytes(value)
  if (!bytes || bytes.length !== 32) {
    return { ok: false }
  }
  return { ok: true, bytes }
}

export function validateMldsa65Verify(value: string): ValidationResult {
  const bytes = decodeBase64UrlBytes(value)
  if (!bytes || bytes.length !== 1952) {
    return { ok: false }
  }
  return { ok: true, bytes }
}

export async function mldsa65PairMatches(seed: string, verify: string): Promise<boolean> {
  const seedCheck = validateMldsa65Seed(seed)
  const verifyCheck = validateMldsa65Verify(verify)
  if (!seedCheck.ok || !verifyCheck.ok || !seedCheck.bytes || !verifyCheck.bytes) {
    return false
  }
  const derived = ml_dsa65.keygen(seedCheck.bytes)
  if (derived.publicKey.length !== verifyCheck.bytes.length) {
    return false
  }
  for (let i = 0; i < derived.publicKey.length; i++) {
    if (derived.publicKey[i] !== verifyCheck.bytes[i]) return false
  }
  return true
}
