package main

import (
	"context"
	"log"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	pb "github.com/sc-lcm/satellite/pkg/grpc"
)

func main() {
	log.Println("😈 Starting Rogue Client (No Certs)...")

	// Try to connect WITHOUT certs
	conn, err := grpc.Dial("localhost:9000", grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		log.Fatalf("Failed to dial: %v", err)
	}
	defer conn.Close()

	client := pb.NewLcmServiceClient(conn)

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	log.Println("Attempting RegisterSatellite...")
	_, err = client.RegisterSatellite(ctx, &pb.RegisterRequest{
		Hostname: "evil-host",
	})

	if err != nil {
		log.Printf("✅ Security Check Passed! Server rejected us: %v", err)
	} else {
		log.Fatalf("❌ Security Check FAILED! Server let us in!")
	}
}
