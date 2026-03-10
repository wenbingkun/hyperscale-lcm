package pxe

import (
	"net/http"
)

// handleMetaData is required by cloud-init nocloud datasource datasource.
// It can just be an empty response or contain instance IDs.
func handleMetaData(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain")
	w.Write([]byte("instance-id: hyperscale-node-generated\n"))
}
