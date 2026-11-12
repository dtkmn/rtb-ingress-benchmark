package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestCheckHealth(t *testing.T) {
	t.Run("healthy response", func(t *testing.T) {
		server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
			response.WriteHeader(http.StatusOK)
		}))
		defer server.Close()

		if err := checkHealth(server.URL); err != nil {
			t.Fatalf("checkHealth() returned an unexpected error: %v", err)
		}
	})

	t.Run("unhealthy response", func(t *testing.T) {
		server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
			response.WriteHeader(http.StatusServiceUnavailable)
		}))
		defer server.Close()

		if err := checkHealth(server.URL); err == nil {
			t.Fatal("checkHealth() returned nil for an unhealthy response")
		}
	})
}
