package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestNewHTTPServerHealthRoutes(t *testing.T) {
	server := newHTTPServer(Config{HTTPPort: "8081"})

	for _, path := range []string{"/live", "/ready"} {
		t.Run(path, func(t *testing.T) {
			recorder := httptest.NewRecorder()
			request := httptest.NewRequest(http.MethodGet, path, nil)

			server.Handler.ServeHTTP(recorder, request)

			if recorder.Code != http.StatusOK {
				t.Fatalf("status = %d, want %d", recorder.Code, http.StatusOK)
			}
		})
	}
}
