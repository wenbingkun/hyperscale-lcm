package main

import (
	"net"
	"testing"
)

func TestGetLocalIP(t *testing.T) {
	ip := getLocalIP()

	// 验证返回的是有效 IP 地址
	if net.ParseIP(ip) == nil {
		t.Errorf("getLocalIP() returned invalid IP: %s", ip)
	}

	// 验证不是空字符串
	if ip == "" {
		t.Error("getLocalIP() returned empty string")
	}

	t.Logf("Detected local IP: %s", ip)
}

func TestGetLocalIPNotEmpty(t *testing.T) {
	ip := getLocalIP()

	// 至少返回 127.0.0.1 作为 fallback
	if ip == "" {
		t.Error("getLocalIP() should never return empty string")
	}
}
