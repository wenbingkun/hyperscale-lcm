package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"flag"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"syscall"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"
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

func main() {
	// Parse flags first so all subsequent code sees resolved values
	serverAddr := flag.String("server", defaultAddress, "Lcm Core gRPC server address")
	certsDir := flag.String("certs", defaultCertDir, "Directory containing mTLS certificates")
	clusterFlag := flag.String("cluster", "default", "Logical cluster or datacenter region name")
	flag.Parse()

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

	// Initialize Docker Executor
	dockerExec, err := docker.NewExecutor()
	if err != nil {
		log.Printf("⚠️ Failed to initialize Docker Client: %v (Docker features disabled)", err)
	}

	// Get Core address from env or flag
	address := os.Getenv("LCM_CORE_ADDR")
	if address == "" {
		address = *serverAddr
	}

	// Get cert directory from env or flag
	certDir := os.Getenv("LCM_CERTS_DIR")
	if certDir == "" {
		certDir = *certsDir
	}
	log.Printf("📁 Using certificate directory: %s", certDir)

	// Load mTLS credentials
	transportCreds := resolveTransportCredentials(certDir)

	// Connect to Core via gRPC with mTLS, KeepAlive, and OpenTelemetry
	conn, err := grpc.Dial(address,
		grpc.WithTransportCredentials(transportCreds),
		grpc.WithKeepaliveParams(kaParams),
		grpc.WithStatsHandler(otelgrpc.NewClientHandler()),
	)
	if err != nil {
		log.Fatalf("did not connect: %v", err)
	}
	defer conn.Close()

	client := pb.NewLcmServiceClient(conn)

	// Gather real system info with hardware specs
	info := BuildRegisterRequest(*clusterFlag) // Pass clusterFlag

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

		// Create a cancellable context for background goroutines
		bgCtx, bgCancel := context.WithCancel(context.Background())
		defer bgCancel()

		// Start Stream Connection in background
		go func() {
			// Reconnection Loop
			for {
				select {
				case <-bgCtx.Done():
					return
				default:
				}

				log.Println("🔌 Connecting to Command Stream...")
				stream, err := client.ConnectStream(bgCtx)
				if err != nil {
					if bgCtx.Err() != nil {
						return
					}
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
						if bgCtx.Err() != nil {
							return
						}
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

		// Start Discovery Manager (DHCP listener + ARP scanner)
		discoveryIface := os.Getenv("LCM_DISCOVERY_IFACE") // e.g. "eth0", empty = auto-detect
		discoveryMgr := discovery.NewManager(client, satelliteId, discoveryIface)
		discoveryMgr.Start(bgCtx)

		// Start Heartbeat Ticker
		ticker := time.NewTicker(5 * time.Second)
		defer ticker.Stop()

		// Start PXE Services for bare-metal provisioning (TFTP + HTTP)
		// DHCP discovery is handled by discoveryMgr above; no separate listener needed.
		go pxe.StartPXEServices(bgCtx, pxe.DefaultConfig)

		// Main loop with graceful shutdown
		for {
			select {
			case <-ticker.C:
				hbReq := BuildHeartbeatRequest(satelliteId, *clusterFlag)
				_, err := client.SendHeartbeat(context.Background(), hbReq)
				if err != nil {
					log.Printf("Heartbeat failed: %v", err)
				} else {
					log.Printf("Heartbeat sent (CPU: %.1f%%, Load: %.2f, Mem: %dMB/%dMB, GPUs: %d)",
						hbReq.CpuUsagePercent, hbReq.LoadAvg,
						hbReq.MemoryUsedBytes/1024/1024, hbReq.MemoryTotalBytes/1024/1024,
						hbReq.GpuCount)
				}

			case sig := <-sigChan:
				log.Printf("Received signal %v, shutting down gracefully...", sig)
				bgCancel()
				discoveryMgr.Stop()
				ticker.Stop()
				// conn.Close() is handled by defer
				log.Println("Satellite Agent stopped")
				return
			}
		}

	} else {
		log.Fatalf("❌ Registration Failed: %s", r.GetMessage())
	}
}

func resolveTransportCredentials(certDir string) credentials.TransportCredentials {
	if usePlaintextGRPC() {
		log.Println("⚠️ LCM_GRPC_PLAINTEXT=true, using insecure gRPC transport for local/demo workflows")
		return insecure.NewCredentials()
	}

	cert, err := tls.LoadX509KeyPair(
		filepath.Join(certDir, "client.pem"),
		filepath.Join(certDir, "client.key"),
	)
	if err != nil {
		log.Fatalf("failed to load client certs: %v", err)
	}

	caCert, err := os.ReadFile(filepath.Join(certDir, "ca.pem"))
	if err != nil {
		log.Fatalf("failed to read CA cert: %v", err)
	}

	caCertPool := x509.NewCertPool()
	if !caCertPool.AppendCertsFromPEM(caCert) {
		log.Fatalf("failed to parse CA certificate from %s", filepath.Join(certDir, "ca.pem"))
	}

	return credentials.NewTLS(&tls.Config{
		Certificates: []tls.Certificate{cert},
		RootCAs:      caCertPool,
		ServerName:   "localhost", // Must match SAN in server cert
	})
}

func usePlaintextGRPC() bool {
	value := os.Getenv("LCM_GRPC_PLAINTEXT")
	if value == "" {
		return false
	}

	parsed, err := strconv.ParseBool(value)
	if err != nil {
		return false
	}
	return parsed
}
