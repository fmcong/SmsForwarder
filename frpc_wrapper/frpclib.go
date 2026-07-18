// Package frpclib provides an Android-compatible wrapper for the frp client library.
package frpclib

import (
	"context"
	"fmt"
	"os"
	"sync"
	"time"

	"github.com/fatedier/frp/client"
	"github.com/fatedier/frp/pkg/config"
	"github.com/fatedier/frp/pkg/config/source"
	"github.com/fatedier/frp/pkg/util/log"
	"github.com/fatedier/frp/pkg/util/version"
)

func init() {
	log.InitLogger("console", "error", 0, false)
}

var (
	mu       sync.Mutex
	services = make(map[int64]*client.Service)
	cancels  = make(map[int64]context.CancelFunc)
)

// GetVersion returns the frp version string.
func GetVersion() string {
	return version.Full()
}

// RunContent creates and starts a frpc service with the given config content.
// uid is used to identify this frpc instance.
// configContent is the TOML config content for frpc.
// Returns true if the service was started successfully.
func RunContent(uid int64, configContent string) bool {
	mu.Lock()
	defer mu.Unlock()

	// If already running, close it first
	if svr, ok := services[uid]; ok {
		svr.GracefulClose(3 * time.Second)
		delete(services, uid)
	}
	if cancel, ok := cancels[uid]; ok {
		cancel()
		delete(cancels, uid)
	}

	// Write config to temp file
	tmpFile, err := os.CreateTemp("", fmt.Sprintf("frpc_%d_*.toml", uid))
	if err != nil {
		return false
	}
	tmpPath := tmpFile.Name()
	if _, err := tmpFile.WriteString(configContent); err != nil {
		tmpFile.Close()
		os.Remove(tmpPath)
		return false
	}
	tmpFile.Close()

	// Load client config from the temp file
	common, proxyCfgs, visitorCfgs, _, err := config.LoadClientConfig(tmpPath, false)
	if err != nil {
		os.Remove(tmpPath)
		return false
	}
	os.Remove(tmpPath)

	// Create config source and populate it
	cfgSource := source.NewConfigSource()
	if err := cfgSource.ReplaceAll(proxyCfgs, visitorCfgs); err != nil {
		return false
	}

	// Create aggregator
	aggregator := source.NewAggregator(cfgSource)

	// Create and run the service
	svr, err := client.NewService(client.ServiceOptions{
		Common:                 common,
		ConfigSourceAggregator: aggregator,
	})
	if err != nil {
		return false
	}

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		_ = svr.Run(ctx)
	}()

	services[uid] = svr
	cancels[uid] = cancel
	return true
}

// IsRunning checks if a frpc service is currently running for the given uid.
func IsRunning(uid int64) bool {
	mu.Lock()
	defer mu.Unlock()

	_, ok := services[uid]
	return ok
}

// Close stops a frpc service for the given uid.
func Close(uid int64) bool {
	mu.Lock()
	defer mu.Unlock()

	if svr, ok := services[uid]; ok {
		svr.GracefulClose(3 * time.Second)
		delete(services, uid)
	}
	if cancel, ok := cancels[uid]; ok {
		cancel()
		delete(cancels, uid)
	}
	return true
}
