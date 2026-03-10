package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/keepalive"

	// OTel instrumentation
	"go.opentelemetry.io/contrib/instrumentation/google.golang.org/grpc/otelgrpc"

	"github.com/sc-lcm/satellite/pkg/discovery"
	"github.com/sc-lcm/satellite/pkg/docker"
	pb "github.com/sc-lcm/satellite/pkg/grpc"
	"github.com/sc-lcm/satellite/pkg/pxe"
)

const (
	defaultAddress = "localhost:9000"
	defaultCertDir = "./certs"
)

// gRPC KeepAlive 参数配置
var kaParams = keepalive.ClientParameters{
	Time:                30 * time.Second, // 每 30 秒发送 ping
	Timeout:             10 * time.Second, // 10 秒超时
	PermitWithoutStream: true,             // 即使没有活跃 stream 也保持连接
}

// getCertDir 获取证书目录，支持环境变量覆盖
func getCertDir() string {
	certDir := os.Getenv("LCM_CERTS_DIR")
	if certDir == "" {
		certDir = defaultCertDir
	}
	return certDir
}

func main() {
	log.Println("Starting Satellite Agent...")

	shutdownTracer, err := initTracer()
	if err != nil {
		log.Printf("⚠️ Failed to initialize tracer: %v", err)
	} else {
		defer shutdownTracer(context.Background())
		log.Println("✨ OpenTelemetry Tracer initialized")
	}

	// 设置优雅关闭信号处理
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)

	// Get Core address from env or default
	address := os.Getenv("LCM_CORE_ADDR")
	if address == "" {
		address = defaultAddress
	}

	// Initialize Docker Executor
	dockerExec, err := docker.NewExecutor()
	if err != nil {
		log.Printf("⚠️ Failed to initialize Docker Client: %v (Docker features disabled)", err)
	}

	// 获取证书目录
	certDir := getCertDir()
	log.Printf("📁 Using certificate directory: %s", certDir)

	// Load mTLS credentials
	cert, err := tls.LoadX509KeyPair(
		filepath.Join(certDir, "client.pem"),
		filepath.Join(certDir, "client.key"),
	)
	if err != nil {
		log.Fatalf("failed to load client certs: %v", err)
	}

	// 使用 os.ReadFile 替代废弃的 ioutil.ReadFile
	caCert, err := os.ReadFile(filepath.Join(certDir, "ca.pem"))
	if err != nil {
		log.Fatalf("failed to read CA cert: %v", err)
	}

	caCertPool := x509.NewCertPool()
	caCertPool.AppendCertsFromPEM(caCert)

	creds := credentials.NewTLS(&tls.Config{
		Certificates: []tls.Certificate{cert},
		RootCAs:      caCertPool,
		ServerName:   "localhost", // Must match SAN in server cert
	})

	// Connect to Core via gRPC with mTLS, KeepAlive, and OpenTelemetry
	conn, err := grpc.Dial(address,
		grpc.WithTransportCredentials(creds),
		grpc.WithKeepaliveParams(kaParams),
		grpc.WithStatsHandler(otelgrpc.NewClientHandler()),
	)
	if err != nil {
		log.Fatalf("did not connect: %v", err)
	}
	defer conn.Close()

	client := pb.NewLcmServiceClient(conn)

	// Gather real system info with hardware specs
	info := BuildRegisterRequest()

	// Register
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	log.Printf("Registering satellite: %s (CPU: %d, RAM: %dGB, GPU: %d x %s)...",
		info.Hostname, info.Hardware.CpuCores, info.Hardware.MemoryGb,
		info.Hardware.GpuCount, info.Hardware.GpuModel)
	r, err := client.RegisterSatellite(ctx, info)
	if err != nil {
		log.Fatalf("could not register satellite: %v", err)
	}

	if r.GetSuccess() {
		log.Printf("✅ Registration Successful! Assigned ID: %s", r.GetAssignedId())
		satelliteId := r.GetAssignedId()

		// Start Stream Connection in background
		go func() {
			// Reconnection Loop
			for {
				log.Println("🔌 Connecting to Command Stream...")
				stream, err := client.ConnectStream(context.Background())
				if err != nil {
					log.Printf("❌ Failed to connect stream: %v. Retrying in 5s...", err)
					time.Sleep(5 * time.Second)
					continue
				}

				// Send initial handshake
				if err := stream.Send(&pb.StreamRequest{
					SatelliteId: satelliteId,
					Payload:     &pb.StreamRequest_Init{Init: true},
				}); err != nil {
					log.Printf("❌ Failed to send handshake: %v. Retrying...", err)
					stream.CloseSend()
					time.Sleep(5 * time.Second)
					continue
				}

				log.Println("✅ Command Stream Connected")

				// Listen for commands
				for {
					resp, err := stream.Recv()
					if err != nil {
						log.Printf("❌ Stream disconnected: %v", err)
						break // Break inner loop to reconnect
					}

					log.Printf("⚡ Received Command [%s]: %s %s", resp.CommandId, resp.CommandType, resp.Payload)

					// Execute Command logic (Refactored or inline)
					handleCommand(resp, satelliteId, dockerExec, stream)
				}

				// Stream disconnected, wait before retry
				time.Sleep(5 * time.Second)
			}
		}()

		// Start Heartbeat Ticker
		ticker := time.NewTicker(5 * time.Second)
		defer ticker.Stop()

		// Start DHCP Listener globally for discovery
		ctxDHCP, cancelDHCP := context.WithCancel(context.Background())
		defer cancelDHCP()
		go discovery.StartDHCPListener(ctxDHCP, client, satelliteId)

		// Start PXE Services globally for baremetal provisioning
		ctxPXE, cancelPXE := context.WithCancel(context.Background())
		defer cancelPXE()
		go pxe.StartPXEServices(ctxPXE, pxe.DefaultConfig)

		// 主循环，包含优雅关闭处理
		for {
			select {
			case <-ticker.C:
				hbReq := BuildHeartbeatRequest(satelliteId)
				_, err := client.SendHeartbeat(context.Background(), hbReq)
				if err != nil {
					log.Printf("⚠️ Heartbeat failed: %v", err)
				} else {
					log.Printf("💓 Heartbeat sent (CPU: %.1f%%, Load: %.2f, Mem: %dMB/%dMB, GPUs: %d)",
						hbReq.CpuUsagePercent, hbReq.LoadAvg,
						hbReq.MemoryUsedBytes/1024/1024, hbReq.MemoryTotalBytes/1024/1024,
						hbReq.GpuCount)
				}

				// Mock Active Discovery (Scan every 10 seconds)
				if time.Now().Unix()%10 < 5 { // Simple throttle
					log.Println("📡 Scanning network range 192.168.1.0/24...")
					// Simulate finding a node
					go func() {
						time.Sleep(2 * time.Second) // Simulate scan time
						discoveredIP := fmt.Sprintf("192.168.1.%d", time.Now().Unix()%254+1)
						log.Printf("🎯 Discovered new asset: %s", discoveredIP)

						// Fire and forget reporting to keep it simple for now
						// In real app, proper error handling
						client.ReportDiscovery(context.Background(), &pb.DiscoveryRequest{
							SatelliteId:     satelliteId,
							DiscoveredIp:    discoveredIP,
							MacAddress:      "AA:BB:CC:DD:EE:FF",
							DiscoveryMethod: "PING_SCAN",
						})
					}()
				}

			case sig := <-sigChan:
				// 优雅关闭处理
				log.Printf("⚠️ Received signal %v, shutting down gracefully...", sig)
				ticker.Stop()
				conn.Close()
				log.Println("👋 Satellite Agent stopped")
				os.Exit(0)
			}
		}

	} else {
		log.Fatalf("❌ Registration Failed: %s", r.GetMessage())
	}
}
