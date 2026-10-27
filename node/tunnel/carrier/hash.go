package carrier

import "crypto/sha256"

func hashCA(value string) [32]byte { return sha256.Sum256([]byte(value)) }
