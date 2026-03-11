package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"math/rand"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"

	pb "github.com/sc-lcm/satellite/pkg/grpc"
)

var (
	serverAddr  = flag.String("addr", "localhost:9000", "The server address in the format of host:port")
	conns       = flag.Int("conns", 100, "Number of physical gRPC connections")
	satsPerConn = flag.Int("sats", 100, "Number of satellites per connection")
	interval    = flag.Duration("interval", 5*time.Second, "Heartbeat interval")
	duration    = flag.Duration("duration", 30*time.Second, "Total load test duration")
	certPath    = flag.String("cert", "../certs/client.pem", "Path to client cert")
	keyPath     = flag.String("key", "../certs/client.key", "Path to client key")
	caPath      = flag.String("ca", "../certs/ca.pem", "Path to CA cert")
	clusterFlag = flag.String("cluster", "default", "Cluster ID for load test isolation")
	plainText   = flag.Bool("plaintext", false, "Use plaintext gRPC instead of TLS")

	registrationErrorLogs int64
)

type stats struct {
	RegistrationAttempts int64 `json:"registrationAttempts"`
	RegistrationSuccess  int64 `json:"registrationSuccess"`
	RegistrationFailures int64 `json:"registrationFailures"`
	HeartbeatAttempts    int64 `json:"heartbeatAttempts"`
	HeartbeatSuccess     int64 `json:"heartbeatSuccess"`
	HeartbeatFailures    int64 `json:"heartbeatFailures"`
}

type summary struct {
	DurationSeconds   int64   `json:"durationSeconds"`
	Connections       int     `json:"connections"`
	SatellitesPerConn int     `json:"satellitesPerConnection"`
	TotalSatellites   int     `json:"totalSatellites"`
	ActiveSatellites  int32   `json:"activeSatellites"`
	RegistrationRate  float64 `json:"registrationSuccessRate"`
	HeartbeatRate     float64 `json:"heartbeatSuccessRate"`
	stats
}

func main() {
	flag.Parse()
	log.Printf("🚀 Starting LoadGen: %d connections x %d sats = %d total satellites", *conns, *satsPerConn, (*conns)*(*satsPerConn))

	creds := loadCredentials()

	ctx, cancel := context.WithTimeout(context.Background(), *duration)
	defer cancel()

	var wg sync.WaitGroup
	var activeSats int32
	var runStats stats

	for i := 0; i < *conns; i++ {
		wg.Add(1)
		go func(connId int) {
			defer wg.Done()
			// Stagger connection start to avoid thundering herd on startup
			select {
			case <-ctx.Done():
				return
			case <-time.After(time.Duration(rand.Intn(5000)) * time.Millisecond):
			}

			conn, err := grpc.Dial(*serverAddr, grpc.WithTransportCredentials(creds))
			if err != nil {
				log.Printf("❌ [Conn %d] Failed to connect: %v", connId, err)
				return
			}
			defer conn.Close()

			client := pb.NewLcmServiceClient(conn)

			// Simulate N satellites on this connection
			var satWg sync.WaitGroup
			for j := 0; j < *satsPerConn; j++ {
				if ctx.Err() != nil {
					break
				}
				satWg.Add(1)
				go func(satIdx int) {
					defer satWg.Done()
					simulateSatellite(ctx, connId, satIdx, client, &activeSats, &runStats)
				}(j)
				// Slight stagger between sats in same conn
				time.Sleep(10 * time.Millisecond)
			}
			satWg.Wait()
		}(i)
	}

	// Monitor loop
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case <-time.After(5 * time.Second):
				current := atomic.LoadInt32(&activeSats)
				log.Printf("📊 Active Satellites: %d / %d", current, (*conns)*(*satsPerConn))
			}
		}
	}()

	wg.Wait()
	printSummary(*duration, *conns, *satsPerConn, atomic.LoadInt32(&activeSats), runStats)
}

func loadCredentials() credentials.TransportCredentials {
	if *plainText {
		log.Printf("🔓 Using plaintext gRPC transport for %s", *serverAddr)
		return insecure.NewCredentials()
	}

	cert, err := tls.LoadX509KeyPair(*certPath, *keyPath)
	if err != nil {
		log.Fatalf("failed to load client certs: %v", err)
	}
	caCert, err := os.ReadFile(*caPath)
	if err != nil {
		log.Fatalf("failed to read CA cert: %v", err)
	}
	caCertPool := x509.NewCertPool()
	if !caCertPool.AppendCertsFromPEM(caCert) {
		log.Fatalf("failed to parse CA certificate from %s", *caPath)
	}

	return credentials.NewTLS(&tls.Config{
		Certificates: []tls.Certificate{cert},
		RootCAs:      caCertPool,
		ServerName:   "localhost",
	})
}

func simulateSatellite(ctx context.Context, connId, satIdx int, client pb.LcmServiceClient, activeCounter *int32,
	runStats *stats) {
	// 1. Register
	hostname := fmt.Sprintf("sat-%d-%d", connId, satIdx)
	regCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	atomic.AddInt64(&runStats.RegistrationAttempts, 1)

	regReq := &pb.RegisterRequest{
		Hostname:     hostname,
		ClusterId:    *clusterFlag,
		IpAddress:    fmt.Sprintf("10.0.%d.%d", connId, satIdx%255),
		OsVersion:    "Linux LoadGen",
		AgentVersion: "1.0-stress",
	}

	resp, err := client.RegisterSatellite(regCtx, regReq)
	if err != nil {
		atomic.AddInt64(&runStats.RegistrationFailures, 1)
		logRegistrationFailure(hostname, err)
		return
	}

	id := resp.GetAssignedId()
	atomic.AddInt64(&runStats.RegistrationSuccess, 1)
	atomic.AddInt32(activeCounter, 1)
	defer atomic.AddInt32(activeCounter, -1)

	// 2. Heartbeat Loop
	ticker := time.NewTicker(*interval)
	// Randomize ticker start to avoid synchronized heartbeats
	select {
	case <-ctx.Done():
		return
	case <-time.After(time.Duration(rand.Intn(int(*interval)))):
	}

	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
		atomic.AddInt64(&runStats.HeartbeatAttempts, 1)
		hbCtx, hbCancel := context.WithTimeout(ctx, 5*time.Second)
		_, err := client.SendHeartbeat(hbCtx, &pb.HeartbeatRequest{
			SatelliteId:     id,
			ClusterId:       *clusterFlag,
			LoadAvg:         rand.Float64() * 10.0,
			MemoryUsedBytes: uint64(rand.Intn(1024*1024*1024)) * 16, // 0-16GB
		})
		hbCancel()
		if err != nil {
			atomic.AddInt64(&runStats.HeartbeatFailures, 1)
			continue
		}
		atomic.AddInt64(&runStats.HeartbeatSuccess, 1)
	}
}

func logRegistrationFailure(hostname string, err error) {
	const maxRegistrationErrorLogs = 5
	if atomic.AddInt64(&registrationErrorLogs, 1) <= maxRegistrationErrorLogs {
		log.Printf("❌ registration failed for %s: %v", hostname, err)
	}
}

func printSummary(duration time.Duration, connections, satellitesPerConn int, activeSatellites int32, runStats stats) {
	totalSatellites := connections * satellitesPerConn
	summary := summary{
		DurationSeconds:   int64(duration.Seconds()),
		Connections:       connections,
		SatellitesPerConn: satellitesPerConn,
		TotalSatellites:   totalSatellites,
		ActiveSatellites:  activeSatellites,
		RegistrationRate:  successRate(runStats.RegistrationSuccess, runStats.RegistrationAttempts),
		HeartbeatRate:     successRate(runStats.HeartbeatSuccess, runStats.HeartbeatAttempts),
		stats:             runStats,
	}

	payload, err := json.Marshal(summary)
	if err != nil {
		log.Printf("LOADGEN_SUMMARY marshal_error=%v", err)
		return
	}
	log.Printf("LOADGEN_SUMMARY %s", payload)
}

func successRate(successes, attempts int64) float64 {
	if attempts == 0 {
		return 0
	}
	return float64(successes) / float64(attempts)
}
