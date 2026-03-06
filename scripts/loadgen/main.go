package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"
)

const (
	baseURL     = "http://localhost:8080"
	concurrency = 50
	totalReg    = 1000
)

type AuthRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

type AuthResponse struct {
	Token string `json:"token"`
}

func main() {
	fmt.Println("🚀 Starting Hyperscale LCM Loadgen...")

	token := login()
	if token == "" {
		fmt.Println("❌ Failed to login, aborting.")
		return
	}
	fmt.Println("✅ Acquired JWT Token")

	fmt.Printf("⚡ Running Allocation API Load Test (%d concurrent workers, %d total requests)...\n", concurrency, totalReg)

	start := time.Now()
	var wg sync.WaitGroup
	reqChan := make(chan int, totalReg)

	successCount := 0
	var mu sync.Mutex

	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for reqID := range reqChan {
				success := simulateAllocation(token, reqID)
				if success {
					mu.Lock()
					successCount++
					mu.Unlock()
				}
			}
		}()
	}

	for i := 0; i < totalReg; i++ {
		reqChan <- i
	}
	close(reqChan)
	wg.Wait()

	duration := time.Since(start)
	qps := float64(totalReg) / duration.Seconds()

	fmt.Println("\n📊 === Benchmark Results ===")
	fmt.Printf("Total Requests: %d\n", totalReg)
	fmt.Printf("Successful:     %d\n", successCount)
	fmt.Printf("Concurrency:    %d\n", concurrency)
	fmt.Printf("Time Taken:     %.2f seconds\n", duration.Seconds())
	fmt.Printf("Throughput:     %.2f req/sec\n", qps)
}

func login() string {
	reqBody, _ := json.Marshal(AuthRequest{Username: "admin", Password: "changeit"})
	resp, err := http.Post(baseURL+"/api/auth/login", "application/json", bytes.NewBuffer(reqBody))
	if err != nil || resp.StatusCode != 200 {
		return ""
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	var authResp AuthResponse
	json.Unmarshal(body, &authResp)
	return authResp.Token
}

func simulateAllocation(token string, id int) bool {
	payload := fmt.Sprintf(`{"jobName":"load-job-%d","cpuRequired":2,"memoryMbRequired":4096,"gpuRequired":1,"priority":50}`, id)
	req, _ := http.NewRequest("POST", baseURL+"/api/v1/allocations", bytes.NewBufferString(payload))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Do(req)

	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode == 201 || resp.StatusCode == 200 || resp.StatusCode == 202
}
