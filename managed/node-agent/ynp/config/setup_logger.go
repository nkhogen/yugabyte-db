package config

import (
	"context"
	"log"
	"node-agent/util"
	"os"
	"os/user"
	"path/filepath"

	apex "github.com/apex/log"
)

const (
	DefaultLogFile    = "app.log"
	DefaultLogDir     = "./logs"
	DefaultMaxSizeMB  = 10
	DefaultMaxBackups = 5
	DefaultMaxAgeDays = 30
)

var (
	ynpLogger *util.AppLogger
)

func Logger() *util.AppLogger {
	return ynpLogger
}

func SetupLogger(config map[string]any) {
	// Default values
	logFile := DefaultLogFile
	logDir := DefaultLogDir
	if len(config) > 0 {
		for _, v := range config {
			if m, ok := v.(map[string]interface{}); ok {
				if lf, ok := m["logfile"].(string); ok && lf != "" {
					logFile = lf
				}
				if ld, ok := m["logdir"].(string); ok && ld != "" {
					logDir = ld
				}
			}
			break // Only use the first key
		}
	}
	// Create log directory if it doesn't exist
	err := os.MkdirAll(logDir, 0755)
	if err != nil {
		log.Fatalf("Failed to create log directory: %v", err)
	}
	logPath := filepath.Join(logDir, logFile)
	ynpLogger = util.NewFileAppLogger(
		logPath,
		DefaultMaxSizeMB,
		DefaultMaxBackups,
		DefaultMaxAgeDays,
		apex.DebugLevel,
		true,
	)
	// Set file permissions
	_ = os.Chmod(logPath, 0644)
	// Set ownership to original user if run with sudo
	origUser := os.Getenv("SUDO_USER")
	if origUser == "" {
		if u, err := user.Current(); err == nil {
			origUser = u.Username
		}
	}
	if origUser != "" {
		if details, err := util.UserInfo(origUser); err == nil {
			if details.UserID == 0 {
				_ = os.Chown(logDir, int(details.UserID), int(details.GroupID))
				_ = os.Chown(logPath, int(details.UserID), int(details.GroupID))
			}
		}
	}
	ynpLogger.Info(context.TODO(), "Logging setup complete in UTC timezone")
}
