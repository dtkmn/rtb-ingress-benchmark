package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"time"

	"github.com/dtkmn/go-adtech-receiver/internal/handlers" // Internal import
	"github.com/dtkmn/go-adtech-receiver/internal/kafka"    // Internal import
	"github.com/gin-gonic/gin"
	metrics "github.com/zsais/go-gin-prometheus"
)

func main() {
	if len(os.Args) == 2 && os.Args[1] == "healthcheck" {
		if err := checkHealth("http://127.0.0.1:8080/health"); err != nil {
			log.Printf("Health check failed: %v", err)
			os.Exit(1)
		}
		return
	}

	// Get Kafka URL from environment
	kafkaURL := os.Getenv("KAFKA_BOOTSTRAP_SERVERS")
	if kafkaURL == "" {
		kafkaURL = "localhost:9092" // Default
		log.Printf("KAFKA_BOOTSTRAP_SERVERS not set, defaulting to %s", kafkaURL)
	}

	// Initialize the Kafka Producer
	// This makes the producer available to the handlers package
	kafka.InitKafkaProducer(kafkaURL)

	// Set up the Gin server
	router := gin.Default()

	// --- Configure Prometheus Metrics ---
	m := metrics.NewPrometheus("gin")
	m.Use(router) // This makes Gin use the middleware

	// --- Define Routes ---
	// We now pass the handler function from the 'handlers' package
	router.POST("/bid-request", handlers.ReceiveBid)

	// Health check endpoint for Docker health checks
	router.GET("/health", func(c *gin.Context) {
		c.JSON(200, gin.H{"status": "healthy"})
	})

	// Run the server
	log.Println("Starting Go AdTech Receiver on port 8080...")
	if err := router.Run(":8080"); err != nil {
		log.Fatalf("Go receiver stopped: %v", err)
	}
}

func checkHealth(endpoint string) error {
	client := http.Client{Timeout: 3 * time.Second}
	response, err := client.Get(endpoint)
	if err != nil {
		return err
	}
	defer response.Body.Close()

	if response.StatusCode < http.StatusOK || response.StatusCode >= http.StatusMultipleChoices {
		return fmt.Errorf("unexpected HTTP status: %s", response.Status)
	}

	return nil
}
