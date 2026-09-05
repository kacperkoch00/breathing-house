package handler

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHealthEndpoints(t *testing.T) {
	h := NewHealth()

	tests := []struct {
		name    string
		handler http.HandlerFunc
		body    string
	}{
		{name: "live", handler: h.GetLive, body: "OK\n"},
		{name: "ready", handler: h.GetReady, body: "READY\n"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			recorder := httptest.NewRecorder()
			request := httptest.NewRequest(http.MethodGet, "/"+tt.name, nil)

			tt.handler(recorder, request)

			if recorder.Code != http.StatusOK {
				t.Fatalf("status = %d, want %d", recorder.Code, http.StatusOK)
			}

			if recorder.Body.String() != tt.body {
				t.Fatalf("body = %q, want %q", recorder.Body.String(), tt.body)
			}

			if contentType := recorder.Header().Get("Content-Type"); contentType != "text/plain; charset=utf-8" {
				t.Fatalf("Content-Type = %q, want %q", contentType, "text/plain; charset=utf-8")
			}
		})
	}
}
