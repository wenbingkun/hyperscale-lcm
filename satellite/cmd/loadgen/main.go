package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
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

	pb "github.com/sc-lcm/satellite/pkg/grpc"
)

var (
	serverAddr  = flag.String("addr", "localhost:9000", "The server address in the format of host:port")
	conns       = flag.Int("conns", 100, "Number of physical gRPC connections")
	satsPerConn = flag.Int("sats", 100, "Number of satellites per connection")
	interval    = flag.Duration("interval", 5*time.Second, "Heartbeat interval")
	certPath    = flag.String("cert", "../certs/client.pem", "Path to client cert")
	keyPath     = flag.String("key", "../certs/client.key", "Path to client key")
	caPath      = flag.String("ca", "../certs/ca.pem", "Path to CA cert")
	clusterFlag = flag.String("cluster", "default", "Cluster ID for load test isolation")
)

func main() {
	flag.Parse()
	log.Printf("🚀 Starting LoadGen: %d connections x %d sats = %d total satellites", *conns, *satsPerConn, (*conns)*(*satsPerConn))

	// Load mTLS creds
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
	creds := credentials.NewTLS(&tls.Config{
		Certificates: []tls.Certificate{cert},
		RootCAs:      caCertPool,
		ServerName:   "localhost",
	})

	var wg sync.WaitGroup
	var activeSats int32

	for i := 0; i < *conns; i++ {
		wg.Add(1)
		go func(connId int) {
			defer wg.Done()
			// Stagger connection start to avoid thundering herd on startup
			time.Sleep(time.Duration(rand.Intn(5000)) * time.Millisecond)

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
				satWg.Add(1)
				go func(satIdx int) {
					defer satWg.Done()
					simulateSatellite(connId, satIdx, client, &activeSats)
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
			time.Sleep(5 * time.Second)
			current := atomic.LoadInt32(&activeSats)
			log.Printf("📊 Active Satellites: %d / %d", current, (*conns)*(*satsPerConn))
		}
	}()

	wg.Wait()
}

func simulateSatellite(connId, satIdx int, client pb.LcmServiceClient, activeCounter *int32) {
	// 1. Register
	hostname := fmt.Sprintf("sat-%d-%d", connId, satIdx)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	regReq := &pb.RegisterRequest{
		Hostname:     hostname,
		ClusterId:    *clusterFlag,
		IpAddress:    fmt.Sprintf("10.0.%d.%d", connId, satIdx%255),
		OsVersion:    "Linux LoadGen",
		AgentVersion: "1.0-stress",
	}

	resp, err := client.RegisterSatellite(ctx, regReq)
	if err != nil {
		//		log.Printf("❌ [%s] Registration failed: %v", hostname, err)
		return
	}

	id := resp.GetAssignedId()
	atomic.AddInt32(activeCounter, 1)

	// 2. Heartbeat Loop
	ticker := time.NewTicker(*interval)
	// Randomize ticker start to avoid synchronized heartbeats
	time.Sleep(time.Duration(rand.Intn(int(*interval))))

	defer ticker.Stop()

	for range ticker.C {
		_, err := client.SendHeartbeat(context.Background(), &pb.HeartbeatRequest{
			SatelliteId:     id,
			ClusterId:       *clusterFlag,
			LoadAvg:         rand.Float64() * 10.0,
			MemoryUsedBytes: uint64(rand.Intn(1024*1024*1024)) * 16, // 0-16GB
		})
		if err != nil {
			log.Printf("⚠️ [%s] Heartbeat failed: %v", hostname, err)
			// Optional: Re-register? For simplified stress test, maybe just retry or exit
		}
	}
}
