package handler

import (
	"net/http"

	"occupancy-monitor/internal/api"
)

type Health struct{}

func NewHealth() *Health {
	return &Health{}
}

func (h *Health) GetLive(w http.ResponseWriter, _ *http.Request) {
	writeHealthResponse(w, http.StatusOK, "OK\n")
}

func (h *Health) GetReady(w http.ResponseWriter, _ *http.Request) {
	writeHealthResponse(w, http.StatusOK, "READY\n")
}

func writeHealthResponse(w http.ResponseWriter, status int, body string) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(body))
}

var _ api.ServerInterface = (*Health)(nil)
