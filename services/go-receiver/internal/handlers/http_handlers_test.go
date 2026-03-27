package handlers

import (
	"bytes"
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/dtkmn/go-adtech-receiver/internal/kafka"
	"github.com/gin-gonic/gin"
	kafkaGo "github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
)

type stubWriter struct {
	err error
}

func (s *stubWriter) WriteMessages(_ context.Context, _ ...kafkaGo.Message) error {
	return s.err
}

func TestReceiveBid(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.POST("/bid-request", ReceiveBid)

	testCases := []struct {
		name           string
		payload        string
		config         kafka.ProducerConfig
		writer         kafka.MessageWriter
		expectedStatus int
		expectedBody   string
	}{
		{
			name:           "ValidBidRequest",
			payload:        `{"id":"123","device":{},"app":{"bundle":"com.example.app"}}`,
			config:         kafka.ProducerConfig{DeliveryMode: kafka.DeliveryModeConfirm},
			writer:         &stubWriter{},
			expectedStatus: http.StatusOK,
			expectedBody:   `{"status":"accepted"}`,
		},
		{
			name:           "InvalidJSON",
			payload:        `{"id":"123","device":`,
			config:         kafka.ProducerConfig{DeliveryMode: kafka.DeliveryModeConfirm},
			writer:         &stubWriter{},
			expectedStatus: http.StatusBadRequest,
			expectedBody:   `{"error":"invalid json payload"}`,
		},
		{
			name:           "LMTEnabled",
			payload:        `{"id":"123","app":{"bundle":"com.example.app"},"device":{"lmt":1}}`,
			config:         kafka.ProducerConfig{DeliveryMode: kafka.DeliveryModeConfirm},
			writer:         &stubWriter{},
			expectedStatus: http.StatusNoContent,
		},
		{
			name:           "AppAndSiteNil",
			payload:        `{"id":"123","device":{}}`,
			config:         kafka.ProducerConfig{DeliveryMode: kafka.DeliveryModeConfirm},
			writer:         &stubWriter{},
			expectedStatus: http.StatusBadRequest,
			expectedBody:   `{"status":"bad request"}`,
		},
		{
			name:           "BlockedIpRange",
			payload:        `{"id":"123","site":{"domain":"example.com"},"device":{"ip":"10.10.2.4","lmt":0}}`,
			config:         kafka.ProducerConfig{DeliveryMode: kafka.DeliveryModeConfirm},
			writer:         &stubWriter{},
			expectedStatus: http.StatusNoContent,
		},
		{
			name:           "KafkaUnavailable",
			payload:        `{"id":"123","site":{"domain":"example.com"},"device":{"lmt":0}}`,
			config:         kafka.ProducerConfig{DeliveryMode: kafka.DeliveryModeConfirm},
			writer:         nil,
			expectedStatus: http.StatusServiceUnavailable,
			expectedBody:   `{"status":"kafka unavailable"}`,
		},
		{
			name:           "KafkaWriteFailure",
			payload:        `{"id":"123","site":{"domain":"example.com"},"device":{"lmt":0}}`,
			config:         kafka.ProducerConfig{DeliveryMode: kafka.DeliveryModeConfirm},
			writer:         &stubWriter{err: errors.New("queue full")},
			expectedStatus: http.StatusServiceUnavailable,
			expectedBody:   `{"status":"kafka buffer full"}`,
		},
		{
			name:           "HttpOnlyModeAcceptsWithoutKafka",
			payload:        `{"id":"123","site":{"domain":"example.com"},"device":{"lmt":0}}`,
			config:         kafka.ProducerConfig{DeliveryMode: kafka.DeliveryModeHttpOnly},
			writer:         nil,
			expectedStatus: http.StatusOK,
			expectedBody:   `{"status":"accepted"}`,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			kafka.ConfigureForTests(tc.config, tc.writer)

			req, _ := http.NewRequest(http.MethodPost, "/bid-request", bytes.NewBufferString(tc.payload))
			req.Header.Set("Content-Type", "application/json")

			w := httptest.NewRecorder()
			router.ServeHTTP(w, req)

			assert.Equal(t, tc.expectedStatus, w.Code)
			if tc.expectedBody != "" {
				assert.JSONEq(t, tc.expectedBody, w.Body.String())
			}
		})
	}
}
