package handler

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHealthEndpoints(t *testing.T) {
	h := NewHealth()

	for _, test := range []struct {
		name string
		call func(http.ResponseWriter, *http.Request)
		body string
	}{
		{name: "live", call: h.GetLive, body: "OK\n"},
		{name: "ready", call: h.GetReady, body: "READY\n"},
	} {
		t.Run(test.name, func(t *testing.T) {
			recorder := httptest.NewRecorder()
			test.call(recorder, httptest.NewRequest(http.MethodGet, "/"+test.name, nil))

			if recorder.Code != http.StatusOK || recorder.Body.String() != test.body {
				t.Fatalf("response = %d %q, want %d %q", recorder.Code, recorder.Body.String(), http.StatusOK, test.body)
			}
		})
	}
}
