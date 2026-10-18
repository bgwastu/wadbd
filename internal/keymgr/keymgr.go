package keymgr

import (
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"math/big"
	"os"
	"os/exec"
	"strings"
	"sync"
)

const (
	AdbKeysPath     = "/data/misc/adb/adb_keys"
	BackupSignature = "ADB_KEYS_BACKUP"
)

var keyMu sync.Mutex

type KeyInfo struct {
	ID          int    `json:"id"`
	Fingerprint string `json:"fingerprint"`
	Comment     string `json:"comment"`
}

// ListKeys returns all authorized ADB keys with fingerprints and comments.
func ListKeys() ([]KeyInfo, error) {
	keyMu.Lock()
	defer keyMu.Unlock()

	data, err := os.ReadFile(AdbKeysPath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}

	lines := strings.Split(string(data), "\n")
	var keys []KeyInfo
	id := 0

	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		parts := strings.Fields(line)
		if len(parts) == 0 {
			continue
		}
		b64Key := parts[0]
		comment := "(unknown)"
		if len(parts) > 1 {
			comment = strings.Join(parts[1:], " ")
		}

		fp := Fingerprint(b64Key)
		if fp == "" {
			// Fallback: SHA256 of raw line
			sum := sha256.Sum256([]byte(line))
			fp = strings.ToUpper(hex.EncodeToString(sum[:8]))
		}

		keys = append(keys, KeyInfo{
			ID:          id,
			Fingerprint: fp,
			Comment:     comment,
		})
		id++
	}

	return keys, nil
}

// RemoveKey deletes a key by its 0-based index.
func RemoveKey(targetID int) error {
	keyMu.Lock()
	defer keyMu.Unlock()

	data, err := os.ReadFile(AdbKeysPath)
	if err != nil {
		return err
	}

	lines := strings.Split(string(data), "\n")
	var newLines []string
	id := 0
	found := false

	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			continue
		}
		if id == targetID {
			found = true
		} else {
			newLines = append(newLines, trimmed)
		}
		id++
	}

	if !found {
		return fmt.Errorf("key ID %d not found", targetID)
	}

	outContent := strings.Join(newLines, "\n")
	if len(newLines) > 0 {
		outContent += "\n"
	}

	if err := writeAdbKeys([]byte(outContent)); err != nil {
		return err
	}

	return nil
}

// ClearKeys removes all authorized keys.
func ClearKeys() error {
	keyMu.Lock()
	defer keyMu.Unlock()

	_ = os.Remove(AdbKeysPath)
	_ = os.Remove("/data/misc/adb/adb_temp_keys.xml")
	return nil
}

// ImportKey validates and appends an adbkey.pub file.
func ImportKey(path string) error {
	keyMu.Lock()
	defer keyMu.Unlock()

	content, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("could not read key file: %w", err)
	}

	keyStr := strings.TrimSpace(string(content))
	fields := strings.Fields(keyStr)
	if len(fields) == 0 {
		return fmt.Errorf("empty key file")
	}

	b64Key := fields[0]
	if !strings.HasPrefix(b64Key, "QAAA") {
		return fmt.Errorf("invalid ADB public key format (must begin with QAAA)")
	}

	raw, err := base64.StdEncoding.DecodeString(b64Key)
	if err != nil || len(raw) < 256 {
		return fmt.Errorf("invalid key payload length: %w", err)
	}

	// Read existing keys
	existingData, _ := os.ReadFile(AdbKeysPath)
	existingStr := string(existingData)
	if strings.Contains(existingStr, b64Key) {
		return fmt.Errorf("key is already authorized")
	}

	var sb strings.Builder
	sb.WriteString(strings.TrimRight(existingStr, "\n"))
	if sb.Len() > 0 {
		sb.WriteString("\n")
	}
	sb.WriteString(keyStr)
	sb.WriteString("\n")

	return writeAdbKeys([]byte(sb.String()))
}

// BackupKeys saves authorized keys to destination with header signature.
func BackupKeys(dstPath string) error {
	keyMu.Lock()
	defer keyMu.Unlock()

	data, err := os.ReadFile(AdbKeysPath)
	if err != nil {
		return fmt.Errorf("adb_keys does not exist: %w", err)
	}

	h := sha256.Sum256([]byte(BackupSignature))
	sigHex := hex.EncodeToString(h[:])

	content := append([]byte(sigHex), data...)
	return os.WriteFile(dstPath, content, 0600)
}

// RestoreKeys restores keys from a backup verified by header signature.
func RestoreKeys(srcPath string) error {
	keyMu.Lock()
	defer keyMu.Unlock()

	content, err := os.ReadFile(srcPath)
	if err != nil {
		return fmt.Errorf("cannot read backup file: %w", err)
	}

	if len(content) < 64 {
		return fmt.Errorf("invalid backup file (too small)")
	}

	h := sha256.Sum256([]byte(BackupSignature))
	expectedSig := hex.EncodeToString(h[:])
	actualSig := string(content[:64])

	if actualSig != expectedSig {
		return fmt.Errorf("backup signature mismatch: file is invalid or corrupted")
	}

	keyData := content[64:]
	return writeAdbKeys(keyData)
}

func writeAdbKeys(data []byte) error {
	tmpFile := AdbKeysPath + ".tmp"
	if err := os.WriteFile(tmpFile, data, 0640); err != nil {
		return err
	}
	if err := os.Rename(tmpFile, AdbKeysPath); err != nil {
		return err
	}
	_ = os.Chmod(AdbKeysPath, 0640)
	// chown 1000:2000 (system:shell)
	_ = exec.Command("chown", "1000:2000", AdbKeysPath).Run()
	_ = exec.Command("chcon", "u:object_r:adb_keys_file:s0", AdbKeysPath).Run()
	return nil
}

// Fingerprint decodes ADB binary public key and generates standard SHA256 digest.
func Fingerprint(b64Key string) string {
	raw, err := base64.StdEncoding.DecodeString(b64Key)
	if err != nil || len(raw) != 524 {
		return ""
	}

	numWords := binary.LittleEndian.Uint32(raw[0:4])
	if numWords != 64 {
		return ""
	}

	// Modulus n (256 bytes, little-endian in ADB binary format)
	nBytesLittle := raw[8 : 8+256]
	nBytesBig := make([]byte, 256)
	for i := 0; i < 256; i++ {
		nBytesBig[i] = nBytesLittle[255-i]
	}
	modulus := new(big.Int).SetBytes(nBytesBig)

	// Exponent (4 bytes at offset 520, little-endian)
	exponent := binary.LittleEndian.Uint32(raw[520:524])
	if exponent == 0 {
		exponent = 65537
	}

	pubKey := &rsa.PublicKey{
		N: modulus,
		E: int(exponent),
	}

	derBytes, err := x509.MarshalPKIXPublicKey(pubKey)
	if err != nil {
		return ""
	}

	hash := sha256.Sum256(derBytes)
	return strings.ToUpper(hex.EncodeToString(hash[:]))
}
