package handlers

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"strings"

	"github.com/dtkmn/go-adtech-receiver/internal/kafka"  // Internal import
	"github.com/dtkmn/go-adtech-receiver/internal/models" // Internal import
	"github.com/gin-gonic/gin"
	kafkaGo "github.com/segmentio/kafka-go"
)

// ReceiveBid is the handler function for POST /bid-request
func ReceiveBid(c *gin.Context) {
	var request models.BidRequest // Use model from 'models' package

	// 1. Deserialize JSON payload
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid json payload"})
		return
	}

	// --- STAGE 1: FAST VALIDATION (Mirrors Quarkus) ---
	if request.ID == "" || request.Device == nil || (request.Site == nil && request.App == nil) {
		c.JSON(http.StatusBadRequest, gin.H{"status": "bad request"})
		return
	}

	// --- STAGE 2: SIMPLE BUSINESS FILTERING (Mirrors Quarkus) ---
	if request.Device.LMT == 1 {
		c.Status(http.StatusNoContent)
		return
	}
	if request.Device.IP != "" && strings.HasPrefix(request.Device.IP, "10.10.") {
		c.Status(http.StatusNoContent)
		return
	}

	if kafka.DeliveryMode() == kafka.DeliveryModeHttpOnly {
		c.JSON(http.StatusOK, gin.H{"status": "accepted"})
		return
	}

	// --- STAGE 3: PUSH TO KAFKA & ACKNOWLEDGE ---
	msgBytes, err := json.Marshal(request)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"status": "serialization error"})
		return
	}

	if kafka.KafkaWriter == nil {
		log.Printf("Kafka writer is not initialized")
		c.JSON(http.StatusServiceUnavailable, gin.H{"status": "kafka unavailable"})
		return
	}

	// Access the producer from the 'kafka' package
	err = kafka.KafkaWriter.WriteMessages(context.Background(),
		kafkaGo.Message{
			Value: msgBytes,
		},
	)
	if err != nil {
		log.Printf("Kafka write error: %v", err)
		c.JSON(http.StatusServiceUnavailable, gin.H{"status": "kafka buffer full"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"status": "accepted"})
}
