package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

type testServer struct{}

func (testServer) GetLive(w http.ResponseWriter, _ *http.Request) {
	w.WriteHeader(http.StatusNoContent)
}

func (testServer) GetReady(w http.ResponseWriter, _ *http.Request) {
	w.WriteHeader(http.StatusCreated)
}

func TestHandlerRegistersRoutes(t *testing.T) {
	handler := Handler(testServer{})

	tests := []struct {
		path       string
		wantStatus int
	}{
		{path: "/live", wantStatus: http.StatusNoContent},
		{path: "/ready", wantStatus: http.StatusCreated},
	}

	for _, tt := range tests {
		t.Run(tt.path, func(t *testing.T) {
			recorder := httptest.NewRecorder()
			request := httptest.NewRequest(http.MethodGet, tt.path, nil)

			handler.ServeHTTP(recorder, request)

			if recorder.Code != tt.wantStatus {
				t.Fatalf("status = %d, want %d", recorder.Code, tt.wantStatus)
			}
		})
	}
}
