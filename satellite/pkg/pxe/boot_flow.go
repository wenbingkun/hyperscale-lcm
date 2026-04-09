package pxe

import (
	"bytes"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"strings"
	"text/template"
)

const defaultKickstartTemplate = `lang en_US.UTF-8
keyboard us
timezone UTC --utc
network --bootproto=dhcp --device=link --activate --hostname={{ .Hostname }}
rootpw --plaintext lcmadmin
reboot
text
skipx
firewall --disabled
selinux --permissive
services --enabled=sshd
url --url="{{ .RepoURL }}"
zerombr
clearpart --all --initlabel
autopart

%packages
@^minimal-environment
curl
%end

%post --log=/root/lcm-post.log
echo "Provisioned by Hyperscale LCM for {{ .Hostname }} ({{ .MAC }})" > /etc/motd
echo "Satellite endpoint: {{ .SatelliteAddress }}" >> /etc/motd
%end
`

type kickstartTemplateData struct {
	MAC              string
	Hostname         string
	RepoURL          string
	KernelURL        string
	InitrdURL        string
	KernelArgs       string
	SatelliteAddress string
}

func newIpxeHandler(cfg ServerConfig) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		mac := strings.TrimSpace(r.URL.Query().Get("mac"))
		displayMAC := mac
		if displayMAC == "" {
			displayMAC = "${net0/mac}"
		}

		hostPort := bootFlowHostPort(cfg, r)
		kickstartURL := fmt.Sprintf(
			"http://%s/kickstart?mac=%s&hostname=%s",
			hostPort,
			kickstartQueryValue(mac, "${net0/mac}"),
			kickstartQueryValue(r.URL.Query().Get("hostname"), "${hostname}"),
		)

		log.Printf("HTTP: Generating iPXE boot flow for MAC %s", displayMAC)

		script := fmt.Sprintf(`#!ipxe
dhcp
isset ${hostname} || set hostname hyperscale-node
set ks-url %s
echo "Booting Hyperscale LCM Managed Node (MAC: %s)"
kernel %s initrd=initrd.img ip=dhcp inst.repo=%s inst.ks=${ks-url} inst.ks.sendmac %s
initrd %s
boot
`, kickstartURL, displayMAC, cfg.InstallKernelURL, cfg.InstallRepoURL, strings.TrimSpace(cfg.InstallKernelArgs), cfg.InstallInitrdURL)

		w.Header().Set("Content-Type", "text/plain")
		_, _ = w.Write([]byte(script))
	}
}

func loadKickstartTemplate(cfg ServerConfig) (*template.Template, error) {
	templateBody := defaultKickstartTemplate
	if strings.TrimSpace(cfg.KickstartTemplate) != "" {
		data, err := os.ReadFile(cfg.KickstartTemplate)
		if err != nil {
			return nil, fmt.Errorf("read kickstart template %s: %w", cfg.KickstartTemplate, err)
		}
		templateBody = string(data)
	}

	return template.New("kickstart").Parse(templateBody)
}

func newKickstartHandler(cfg ServerConfig, tmpl *template.Template) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		data := kickstartTemplateData{
			MAC:              requestNodeMAC(r),
			Hostname:         requestNodeHostname(r),
			RepoURL:          cfg.InstallRepoURL,
			KernelURL:        cfg.InstallKernelURL,
			InitrdURL:        cfg.InstallInitrdURL,
			KernelArgs:       strings.TrimSpace(cfg.InstallKernelArgs),
			SatelliteAddress: bootFlowHostPort(cfg, r),
		}

		var rendered bytes.Buffer
		if err := tmpl.Execute(&rendered, data); err != nil {
			http.Error(w, "failed to render kickstart", http.StatusInternalServerError)
			return
		}

		log.Printf("HTTP: Rendering kickstart for MAC %s hostname %s", data.MAC, data.Hostname)
		w.Header().Set("Content-Type", "text/plain")
		_, _ = w.Write(rendered.Bytes())
	}
}

func kickstartQueryValue(value string, placeholder string) string {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" {
		return placeholder
	}
	return url.QueryEscape(trimmed)
}

func bootFlowHostPort(cfg ServerConfig, r *http.Request) string {
	if strings.TrimSpace(cfg.BootServerHost) != "" {
		return advertisedHTTPHostPort(cfg)
	}

	if host := strings.TrimSpace(r.Host); host != "" {
		return host
	}

	return advertisedHTTPHostPort(cfg)
}

func requestNodeMAC(r *http.Request) string {
	mac := strings.TrimSpace(r.URL.Query().Get("mac"))
	if mac == "" || strings.Contains(mac, "${") {
		return "unknown"
	}
	return mac
}

func requestNodeHostname(r *http.Request) string {
	hostname := strings.TrimSpace(r.URL.Query().Get("hostname"))
	if hostname != "" && !strings.Contains(hostname, "${") {
		return hostname
	}

	if mac := requestNodeMAC(r); mac != "unknown" {
		sanitized := strings.NewReplacer(":", "", "-", "", ".", "").Replace(strings.ToLower(mac))
		return "node-" + sanitized
	}

	return "hyperscale-node"
}
