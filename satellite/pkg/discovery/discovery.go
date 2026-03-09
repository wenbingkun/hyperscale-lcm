// Package discovery provides network device auto-discovery capabilities
// including DHCP snooping, ARP scanning, and MAC OUI vendor resolution.
package discovery

import (
	"context"
	"log"
	"net"
	"sync"

	pb "github.com/sc-lcm/satellite/pkg/grpc"
)

// Event represents a discovered device on the network.
type Event struct {
	IP     net.IP
	MAC    net.HardwareAddr
	Method string // "DHCP", "ARP_SCAN"
}

// Reporter sends discovery events to the Core via gRPC.
type Reporter struct {
	client      pb.LcmServiceClient
	satelliteID string
}

// NewReporter creates a new Reporter.
func NewReporter(client pb.LcmServiceClient, satelliteID string) *Reporter {
	return &Reporter{client: client, satelliteID: satelliteID}
}

// Report sends a single discovery event to Core.
func (r *Reporter) Report(ctx context.Context, ev Event) {
	vendor := ResolveOUI(ev.MAC)
	log.Printf("DISCOVERY [%s] IP=%s MAC=%s Vendor=%s", ev.Method, ev.IP, ev.MAC, vendor)

	resp, err := r.client.ReportDiscovery(ctx, &pb.DiscoveryRequest{
		SatelliteId:     r.satelliteID,
		DiscoveredIp:    ev.IP.String(),
		MacAddress:      ev.MAC.String(),
		DiscoveryMethod: ev.Method,
	})
	if err != nil {
		log.Printf("Failed to report discovery: %v", err)
		return
	}
	if !resp.GetSuccess() {
		log.Printf("Core rejected discovery: %s", resp.GetMessage())
	}
}

// Manager orchestrates all discovery sources (DHCP listener + ARP scanner).
type Manager struct {
	reporter *Reporter
	iface    string
	events   chan Event
	cancel   context.CancelFunc
	wg       sync.WaitGroup
}

// NewManager creates a discovery Manager bound to the given network interface.
// If iface is empty, the first non-loopback interface is used.
func NewManager(client pb.LcmServiceClient, satelliteID, iface string) *Manager {
	if iface == "" {
		iface = detectInterface()
	}
	return &Manager{
		reporter: NewReporter(client, satelliteID),
		iface:    iface,
		events:   make(chan Event, 64),
	}
}

// Start launches DHCP listener and periodic ARP scanner in background goroutines.
// All goroutines are cancelled when Stop() is called.
func (m *Manager) Start(parentCtx context.Context) {
	ctx, cancel := context.WithCancel(parentCtx)
	m.cancel = cancel

	// Event consumer — reports discoveries to Core
	m.wg.Add(1)
	go func() {
		defer m.wg.Done()
		for {
			select {
			case <-ctx.Done():
				return
			case ev := <-m.events:
				m.reporter.Report(ctx, ev)
			}
		}
	}()

	// DHCP Listener
	m.wg.Add(1)
	go func() {
		defer m.wg.Done()
		ListenDHCP(ctx, m.iface, m.events)
	}()

	// ARP Scanner (periodic)
	m.wg.Add(1)
	go func() {
		defer m.wg.Done()
		RunARPScanner(ctx, m.iface, m.events)
	}()

	log.Printf("Discovery manager started on interface %s", m.iface)
}

// Stop gracefully shuts down all discovery goroutines.
func (m *Manager) Stop() {
	if m.cancel != nil {
		m.cancel()
	}
	m.wg.Wait()
	log.Println("Discovery manager stopped")
}

// detectInterface returns the name of the first non-loopback, up, IPv4 interface.
func detectInterface() string {
	ifaces, err := net.Interfaces()
	if err != nil {
		log.Printf("Failed to list interfaces: %v", err)
		return "eth0"
	}
	for _, i := range ifaces {
		if i.Flags&net.FlagLoopback != 0 || i.Flags&net.FlagUp == 0 {
			continue
		}
		addrs, err := i.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			if ipnet, ok := addr.(*net.IPNet); ok && ipnet.IP.To4() != nil {
				return i.Name
			}
		}
	}
	return "eth0"
}
