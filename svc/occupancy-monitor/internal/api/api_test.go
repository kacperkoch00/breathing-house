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

	for _, test := range []struct {
		path       string
		wantStatus int
	}{
		{path: "/live", wantStatus: http.StatusNoContent},
		{path: "/ready", wantStatus: http.StatusCreated},
	} {
		t.Run(test.path, func(t *testing.T) {
			recorder := httptest.NewRecorder()
			request := httptest.NewRequest(http.MethodGet, test.path, nil)

			handler.ServeHTTP(recorder, request)

			if recorder.Code != test.wantStatus {
				t.Fatalf("status = %d, want %d", recorder.Code, test.wantStatus)
			}
		})
	}
}
