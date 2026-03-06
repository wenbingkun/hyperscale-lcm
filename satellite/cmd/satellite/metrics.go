package main

import (
	"bufio"
	"fmt"
	"log"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"time"

	pb "github.com/sc-lcm/satellite/pkg/grpc"
	"github.com/sc-lcm/satellite/pkg/redfish"
)

var rfCollector = redfish.NewCollector()

// SystemMetrics holds collected host metrics
type SystemMetrics struct {
	CPUUsagePercent  float64
	LoadAvg1m        float64
	MemoryUsedBytes  uint64
	MemoryTotalBytes uint64
	DiskUsedBytes    uint64
	DiskTotalBytes   uint64
	GPUCount         int32
	GPUs             []*pb.GpuMetric
}

// HardwareInfo holds static hardware info reported at registration
type HardwareInfo struct {
	CPUCores int32
	MemoryGB int64
	GPUCount int32
	GPUModel string
}

// CollectHardwareInfo gathers static hardware information for registration
func CollectHardwareInfo() *HardwareInfo {
	info := &HardwareInfo{
		CPUCores: int32(runtime.NumCPU()),
	}

	// Memory
	info.MemoryGB = int64(getMemoryTotalGB())

	// GPU detection
	gpuCount, gpuModel := detectGPU()
	info.GPUCount = gpuCount
	info.GPUModel = gpuModel

	log.Printf("📊 Hardware: %d CPU cores, %d GB RAM, %d GPUs (%s)",
		info.CPUCores, info.MemoryGB, info.GPUCount, info.GPUModel)

	return info
}

// CollectMetrics gathers current system metrics for heartbeat
func CollectMetrics() *SystemMetrics {
	m := &SystemMetrics{}

	// Load average
	m.LoadAvg1m = getLoadAvg()

	// CPU usage
	m.CPUUsagePercent = getCPUUsage()

	// Memory
	memUsed, memTotal := getMemoryInfo()
	m.MemoryUsedBytes = memUsed
	m.MemoryTotalBytes = memTotal

	// Disk
	diskUsed, diskTotal := getDiskInfo()
	m.DiskUsedBytes = diskUsed
	m.DiskTotalBytes = diskTotal

	// GPU metrics
	m.GPUs = getGPUMetrics()
	m.GPUCount = int32(len(m.GPUs))

	return m
}

// getLoadAvg reads 1-minute load average from /proc/loadavg
func getLoadAvg() float64 {
	data, err := os.ReadFile("/proc/loadavg")
	if err != nil {
		return 0.0
	}
	fields := strings.Fields(string(data))
	if len(fields) < 1 {
		return 0.0
	}
	val, err := strconv.ParseFloat(fields[0], 64)
	if err != nil {
		return 0.0
	}
	return val
}

// getCPUUsage calculates CPU usage from /proc/stat over a short interval
func getCPUUsage() float64 {
	idle1, total1 := readCPUStat()
	time.Sleep(200 * time.Millisecond)
	idle2, total2 := readCPUStat()

	idleDelta := float64(idle2 - idle1)
	totalDelta := float64(total2 - total1)
	if totalDelta == 0 {
		return 0.0
	}
	return (1.0 - idleDelta/totalDelta) * 100.0
}

func readCPUStat() (idle, total uint64) {
	data, err := os.ReadFile("/proc/stat")
	if err != nil {
		return 0, 0
	}
	lines := strings.Split(string(data), "\n")
	for _, line := range lines {
		if strings.HasPrefix(line, "cpu ") {
			fields := strings.Fields(line)
			if len(fields) < 5 {
				return 0, 0
			}
			var values []uint64
			for _, f := range fields[1:] {
				v, _ := strconv.ParseUint(f, 10, 64)
				values = append(values, v)
			}
			for _, v := range values {
				total += v
			}
			if len(values) >= 4 {
				idle = values[3] // idle is the 4th field
			}
			return idle, total
		}
	}
	return 0, 0
}

// getMemoryInfo reads memory from /proc/meminfo
func getMemoryInfo() (used, total uint64) {
	f, err := os.Open("/proc/meminfo")
	if err != nil {
		return 0, 0
	}
	defer f.Close()

	var memTotal, memAvailable uint64
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "MemTotal:") {
			memTotal = parseMemInfoValue(line)
		} else if strings.HasPrefix(line, "MemAvailable:") {
			memAvailable = parseMemInfoValue(line)
		}
	}
	total = memTotal * 1024 // Convert kB to bytes
	used = (memTotal - memAvailable) * 1024
	return used, total
}

func parseMemInfoValue(line string) uint64 {
	fields := strings.Fields(line)
	if len(fields) < 2 {
		return 0
	}
	val, err := strconv.ParseUint(fields[1], 10, 64)
	if err != nil {
		return 0
	}
	return val // Value is in kB
}

func getMemoryTotalGB() int {
	f, err := os.Open("/proc/meminfo")
	if err != nil {
		return 0
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "MemTotal:") {
			kbytes := parseMemInfoValue(line)
			return int(kbytes / 1024 / 1024) // kB -> GB
		}
	}
	return 0
}

// getDiskInfo returns used and total bytes for root filesystem
func getDiskInfo() (used, total uint64) {
	output, err := exec.Command("df", "-B1", "/").Output()
	if err != nil {
		return 0, 0
	}
	lines := strings.Split(string(output), "\n")
	if len(lines) < 2 {
		return 0, 0
	}
	fields := strings.Fields(lines[1])
	if len(fields) < 4 {
		return 0, 0
	}
	total, _ = strconv.ParseUint(fields[1], 10, 64)
	used, _ = strconv.ParseUint(fields[2], 10, 64)
	return used, total
}

// detectGPU detects GPU count and model using nvidia-smi
func detectGPU() (int32, string) {
	output, err := exec.Command("nvidia-smi",
		"--query-gpu=name",
		"--format=csv,noheader,nounits").Output()
	if err != nil {
		return 0, ""
	}
	lines := strings.Split(strings.TrimSpace(string(output)), "\n")
	if len(lines) == 0 || lines[0] == "" {
		return 0, ""
	}
	// Use first GPU's model name, simplify
	model := extractGPUModel(lines[0])
	return int32(len(lines)), model
}

// extractGPUModel simplifies "NVIDIA A100-SXM4-80GB" -> "A100"
func extractGPUModel(fullName string) string {
	fullName = strings.TrimSpace(fullName)
	// Remove NVIDIA prefix
	fullName = strings.TrimPrefix(fullName, "NVIDIA ")
	// Take first part before dash
	parts := strings.Split(fullName, "-")
	if len(parts) > 0 {
		return strings.TrimSpace(parts[0])
	}
	return fullName
}

// getGPUMetrics collects per-GPU metrics via nvidia-smi
func getGPUMetrics() []*pb.GpuMetric {
	output, err := exec.Command("nvidia-smi",
		"--query-gpu=index,name,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw",
		"--format=csv,noheader,nounits").Output()
	if err != nil {
		return nil
	}

	var metrics []*pb.GpuMetric
	lines := strings.Split(strings.TrimSpace(string(output)), "\n")
	for _, line := range lines {
		if line == "" {
			continue
		}
		fields := strings.Split(line, ", ")
		if len(fields) < 7 {
			continue
		}

		idx, _ := strconv.Atoi(strings.TrimSpace(fields[0]))
		util, _ := strconv.ParseFloat(strings.TrimSpace(fields[2]), 64)
		memUsed, _ := strconv.ParseUint(strings.TrimSpace(fields[3]), 10, 64)
		memTotal, _ := strconv.ParseUint(strings.TrimSpace(fields[4]), 10, 64)
		temp, _ := strconv.Atoi(strings.TrimSpace(fields[5]))
		power, _ := strconv.ParseFloat(strings.TrimSpace(fields[6]), 64)

		metrics = append(metrics, &pb.GpuMetric{
			Index:              int32(idx),
			Name:               strings.TrimSpace(fields[1]),
			UtilizationPercent: util,
			MemoryUsedBytes:    memUsed * 1024 * 1024, // MiB -> bytes
			MemoryTotalBytes:   memTotal * 1024 * 1024,
			TemperatureCelsius: int32(temp),
			PowerWatts:         int32(power),
		})
	}

	return metrics
}

// BuildHeartbeatRequest creates a heartbeat request from collected metrics
func BuildHeartbeatRequest(satelliteId string) *pb.HeartbeatRequest {
	m := CollectMetrics()
	powerState, tempC := rfCollector.CollectDynamicTelemetry()

	return &pb.HeartbeatRequest{
		SatelliteId:              satelliteId,
		LoadAvg:                  m.LoadAvg1m,
		MemoryUsedBytes:          m.MemoryUsedBytes,
		CpuUsagePercent:          m.CPUUsagePercent,
		MemoryTotalBytes:         m.MemoryTotalBytes,
		DiskUsedBytes:            m.DiskUsedBytes,
		DiskTotalBytes:           m.DiskTotalBytes,
		GpuCount:                 m.GPUCount,
		GpuMetrics:               m.GPUs,
		PowerState:               powerState,
		SystemTemperatureCelsius: tempC,
	}
}

// BuildRegisterRequest creates a registration request with real hardware info
func BuildRegisterRequest() *pb.RegisterRequest {
	hostname, _ := os.Hostname()
	localIP := getLocalIP()
	hw := CollectHardwareInfo()

	osVersion := fmt.Sprintf("Linux %s", getKernelVersion())

	hwSpecs := &pb.HardwareSpecs{
		CpuCores: hw.CPUCores,
		MemoryGb: hw.MemoryGB,
		GpuCount: hw.GPUCount,
		GpuModel: hw.GPUModel,
	}
	rfCollector.EnrichSpecs(hwSpecs)

	return &pb.RegisterRequest{
		Hostname:     hostname,
		IpAddress:    localIP,
		OsVersion:    osVersion,
		AgentVersion: "0.3.0",
		Hardware:     hwSpecs,
	}
}

// getKernelVersion reads kernel version
func getKernelVersion() string {
	data, err := os.ReadFile("/proc/version")
	if err != nil {
		return "unknown"
	}
	fields := strings.Fields(string(data))
	if len(fields) >= 3 {
		return fields[2]
	}
	return "unknown"
}
