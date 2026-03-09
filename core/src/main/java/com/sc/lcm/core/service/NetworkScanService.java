package com.sc.lcm.core.service;

import com.sc.lcm.core.domain.DiscoveredDevice;
import com.sc.lcm.core.domain.ScanJob;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 网络扫描服务
 * 
 * 支持 CIDR 和 IP 范围扫描，检测开放端口并自动添加到发现池
 */
@ApplicationScoped
@Slf4j
public class NetworkScanService {

    @Inject
    Vertx vertx;

    private final ExecutorService scanExecutor = Executors.newFixedThreadPool(50);
    private volatile boolean cancelRequested = false;

    @PreDestroy
    void shutdown() {
        scanExecutor.shutdownNow();
    }

    /**
     * 解析 CIDR 为 IP 列表
     * 例如: 192.168.1.0/24 -> 192.168.1.1 - 192.168.1.254
     */
    public List<String> parseCidr(String cidr) {
        List<String> ips = new ArrayList<>();

        try {
            if (cidr.contains("/")) {
                String[] parts = cidr.split("/");
                String baseIp = parts[0];
                int prefixLen = Integer.parseInt(parts[1]);

                String[] octets = baseIp.split("\\.");
                long baseAddr = 0;
                for (String octet : octets) {
                    baseAddr = (baseAddr << 8) | Integer.parseInt(octet);
                }

                int numHosts = (int) Math.pow(2, 32 - prefixLen);
                // Skip network and broadcast for /24 and smaller
                int start = prefixLen >= 24 ? 1 : 0;
                int end = prefixLen >= 24 ? numHosts - 1 : numHosts;

                for (int i = start; i < end && i < 1024; i++) { // Limit to 1024 IPs
                    long addr = baseAddr + i;
                    String ip = String.format("%d.%d.%d.%d",
                            (addr >> 24) & 0xFF,
                            (addr >> 16) & 0xFF,
                            (addr >> 8) & 0xFF,
                            addr & 0xFF);
                    ips.add(ip);
                }
            } else if (cidr.contains("-")) {
                // Range format: 192.168.1.1-192.168.1.100
                String[] parts = cidr.split("-");
                String startIp = parts[0].trim();
                String endIp = parts[1].trim();

                String[] startOctets = startIp.split("\\.");
                String[] endOctets = endIp.split("\\.");

                int startLast = Integer.parseInt(startOctets[3]);
                int endLast = Integer.parseInt(endOctets[3]);
                String prefix = startOctets[0] + "." + startOctets[1] + "." + startOctets[2] + ".";

                for (int i = startLast; i <= endLast && i <= startLast + 1024; i++) {
                    ips.add(prefix + i);
                }
            } else {
                // Single IP
                ips.add(cidr);
            }
        } catch (Exception e) {
            log.error("Failed to parse CIDR: {}", cidr, e);
        }

        return ips;
    }

    /**
     * 解析端口列表
     */
    public List<Integer> parsePorts(String portSpec) {
        List<Integer> ports = new ArrayList<>();
        if (portSpec == null || portSpec.isEmpty()) {
            ports.add(22); // SSH
            ports.add(8080); // HTTP
            ports.add(9000); // gRPC
            ports.add(623); // IPMI/BMC
            return ports;
        }

        for (String part : portSpec.split(",")) {
            try {
                ports.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException e) {
                log.warn("Invalid port: {}", part);
            }
        }
        return ports;
    }

    /**
     * 探测单个 IP 的端口
     */
    public CompletableFuture<ScanResult> probeHost(String ip, List<Integer> ports, int timeoutMs) {
        return CompletableFuture.supplyAsync(() -> {
            ScanResult result = new ScanResult(ip);

            for (int port : ports) {
                if (cancelRequested)
                    break;

                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(ip, port), timeoutMs);
                    result.openPorts.add(port);
                } catch (Exception e) {
                    // Port closed or unreachable
                }
            }

            // Try reverse DNS lookup if any port is open
            if (!result.openPorts.isEmpty()) {
                try {
                    InetAddress addr = InetAddress.getByName(ip);
                    String hostname = addr.getCanonicalHostName();
                    if (!hostname.equals(ip)) {
                        result.hostname = hostname;
                    }
                } catch (Exception e) {
                    // DNS lookup failed
                }

                // Infer device type based on open ports
                result.inferredType = inferDeviceType(result.openPorts);
            }

            return result;
        }, scanExecutor);
    }

    /**
     * 根据开放端口推断设备类型
     */
    private String inferDeviceType(List<Integer> openPorts) {
        if (openPorts.contains(623))
            return "BMC_ENABLED";
        if (openPorts.contains(9000))
            return "LCM_AGENT";
        if (openPorts.contains(22))
            return "COMPUTE_NODE";
        return "UNKNOWN";
    }

    /**
     * 执行扫描任务
     */
    public Uni<Void> executeScan(ScanJob job) {
        cancelRequested = false;
        List<String> ips = parseCidr(job.getTarget());
        List<Integer> ports = parsePorts(job.getPorts());

        job.setTotalIps(ips.size());
        job.setStatus(ScanJob.ScanStatus.RUNNING);
        job.setStartedAt(LocalDateTime.now());

        log.info("🔍 Starting network scan: {} ({} IPs, ports: {})",
                job.getTarget(), ips.size(), ports);

        return Panache.withTransaction(() -> job.persist())
                .chain(() -> {
                    // Run scan in background on a Vert.x worker thread
                    vertx.executeBlocking(() -> {
                        int discovered = 0;
                        int scanned = 0;

                        List<CompletableFuture<ScanResult>> futures = new ArrayList<>();

                        for (String ip : ips) {
                            if (cancelRequested)
                                break;
                            futures.add(probeHost(ip, ports, 500));
                        }

                        for (CompletableFuture<ScanResult> future : futures) {
                            try {
                                ScanResult result = future.get(2, TimeUnit.SECONDS);
                                scanned++;

                                if (!result.openPorts.isEmpty()) {
                                    discovered++;
                                    saveDiscoveredDevice(result);
                                }

                                // Update progress
                                int progress = (scanned * 100) / ips.size();
                                if (progress % 10 == 0) {
                                    updateScanProgress(job.getId(), scanned, discovered, progress);
                                }
                            } catch (Exception e) {
                                scanned++;
                            }
                        }

                        completeScan(job.getId(), scanned, discovered, null);
                        return null;
                    }).subscribe().with(
                            v -> {},
                            e -> log.error("Scan execution failed for job {}", job.getId(), e));

                    return Uni.createFrom().voidItem();
                });
    }

    private void saveDiscoveredDevice(ScanResult result) {
        Panache.withTransaction(() -> DiscoveredDevice.findByIp(result.ip)
                .flatMap(existing -> {
                    if (existing != null) {
                        existing.setLastProbedAt(LocalDateTime.now());
                        existing.setOpenPorts(result.openPorts.toString());
                        return existing.persist();
                    }

                    DiscoveredDevice device = new DiscoveredDevice();
                    device.setIpAddress(result.ip);
                    device.setHostname(result.hostname);
                    device.setDiscoveryMethod(DiscoveredDevice.DiscoveryMethod.SCAN);
                    device.setInferredType(result.inferredType);
                    device.setOpenPorts(result.openPorts.toString());
                    device.setDiscoveredAt(LocalDateTime.now());

                    log.info("📡 Discovered: {} ({}) - ports: {}",
                            result.ip, result.inferredType, result.openPorts);

                    return device.persist();
                })).subscribe().with(
                        v -> {
                        },
                        e -> log.error("Failed to save discovered device: {}", result.ip, e));
    }

    private void updateScanProgress(String jobId, int scanned, int discovered, int progress) {
        Panache.withTransaction(() -> ScanJob.<ScanJob>findById(jobId)
                .onItem().ifNotNull().invoke(job -> {
                    job.setScannedCount(scanned);
                    job.setDiscoveredCount(discovered);
                    job.setProgressPercent(progress);
                })).subscribe().with(v -> {
                }, e -> {
                });
    }

    private void completeScan(String jobId, int scanned, int discovered, String error) {
        Panache.withTransaction(() -> ScanJob.<ScanJob>findById(jobId)
                .onItem().ifNotNull().invoke(job -> {
                    job.setScannedCount(scanned);
                    job.setDiscoveredCount(discovered);
                    job.setProgressPercent(100);
                    job.setCompletedAt(LocalDateTime.now());
                    job.setStatus(error != null ? ScanJob.ScanStatus.FAILED : ScanJob.ScanStatus.COMPLETED);
                    job.setErrorMessage(error);

                    log.info("✅ Scan completed: {} IPs scanned, {} discovered", scanned, discovered);
                })).subscribe().with(v -> {
                }, e -> log.error("Failed to complete scan", e));
    }

    public void cancelScan() {
        cancelRequested = true;
    }

    // ============== Result DTO ==============

    public static class ScanResult {
        public String ip;
        public String hostname;
        public List<Integer> openPorts = new ArrayList<>();
        public String inferredType;

        public ScanResult(String ip) {
            this.ip = ip;
        }
    }
}
