package props

import (
	"bytes"
	"os"
	"os/exec"
	"strings"
)

var resetpropCandidates = []string{
	"/data/adb/ksu/bin/resetprop",
	"/data/adb/magisk/resetprop",
	"/data/adb/ap/bin/resetprop",
}

// FindResetprop returns the path to a usable resetprop binary if present.
func FindResetprop() string {
	for _, c := range resetpropCandidates {
		if fi, err := os.Stat(c); err == nil && (fi.Mode()&0111) != 0 {
			return c
		}
	}
	if p, err := exec.LookPath("resetprop"); err == nil {
		return p
	}
	return ""
}

// Get reads an Android system property.
func Get(name string) string {
	cmd := exec.Command("/system/bin/getprop", name)
	var out bytes.Buffer
	cmd.Stdout = &out
	if err := cmd.Run(); err == nil {
		return strings.TrimSpace(out.String())
	}
	return ""
}

// Set sets an Android property using setprop.
func Set(name, value string) error {
	cmd := exec.Command("/system/bin/setprop", name, value)
	return cmd.Run()
}

// SetPersistent sets an Android property using resetprop if available, or setprop.
func SetPersistent(name, value string) error {
	rp := FindResetprop()
	if rp != "" {
		cmd := exec.Command(rp, "-n", name, value)
		if err := cmd.Run(); err == nil {
			return nil
		}
	}
	return Set(name, value)
}
