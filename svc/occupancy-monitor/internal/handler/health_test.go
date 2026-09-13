package handler

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHealthEndpoints(t *testing.T) {
	readiness := NewReadiness()
	h := NewHealth(readiness)

	tests := []struct {
		name    string
		handler http.HandlerFunc
		status  int
		body    string
	}{
		{
			name:    "live",
			handler: h.GetLive,
			status:  http.StatusOK,
			body:    "OK\n",
		},
		{
			name:    "ready",
			handler: h.GetReady,
			status:  http.StatusServiceUnavailable,
			body:    "NOT READY\n",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			recorder := httptest.NewRecorder()
			request := httptest.NewRequest(http.MethodGet, "/"+tt.name, nil)

			tt.handler(recorder, request)

			if recorder.Code != tt.status {
				t.Fatalf("status = %d, want %d", recorder.Code, tt.status)
			}

			if recorder.Body.String() != tt.body {
				t.Fatalf("body = %q, want %q", recorder.Body.String(), tt.body)
			}

			if contentType := recorder.Header().Get("Content-Type"); contentType != "text/plain; charset=utf-8" {
				t.Fatalf("Content-Type = %q, want %q", contentType, "text/plain; charset=utf-8")
			}
		})
	}

	t.Run("ready after Kafka becomes healthy", func(t *testing.T) {
		readiness.SetReady(true)

		recorder := httptest.NewRecorder()
		request := httptest.NewRequest(http.MethodGet, "/ready", nil)

		h.GetReady(recorder, request)

		if recorder.Code != http.StatusOK {
			t.Fatalf("status = %d, want %d", recorder.Code, http.StatusOK)
		}

		if recorder.Body.String() != "READY\n" {
			t.Fatalf("body = %q, want %q", recorder.Body.String(), "READY\n")
		}
	})
}
