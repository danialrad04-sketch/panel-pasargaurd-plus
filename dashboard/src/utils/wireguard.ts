import { encode } from '@stablelib/base64'
import { generateKeyPair } from '@stablelib/x25519'

export interface WireGuardKeyPair {
  privateKey: string
  publicKey: string
}

export function generateWireGuardKeyPair(): WireGuardKeyPair {
  const keyPair = generateKeyPair()
  return {
    privateKey: encode(keyPair.secretKey),
    publicKey: encode(keyPair.publicKey),
  }
}
