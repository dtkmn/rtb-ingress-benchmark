package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/dtkmn/go-adtech-receiver/internal/handlers" // Internal import
	"github.com/dtkmn/go-adtech-receiver/internal/kafka"    // Internal import
)

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	portNumber, err := strconv.Atoi(port)
	if err != nil || portNumber < 1 || portNumber > 65535 {
		log.Fatal("PORT must be an integer from 1 to 65535")
	}
	if len(os.Args) == 2 && os.Args[1] == "healthcheck" {
		if err := checkHealth("http://127.0.0.1:" + port + "/health"); err != nil {
			log.Printf("Health check failed: %v", err)
			os.Exit(1)
		}
		return
	}

	// Get Kafka URL from environment
	profile, err := telemetryProfile(os.Getenv("BENCHMARK_TELEMETRY_PROFILE"))
	if err != nil {
		log.Fatal(err)
	}
	kafkaURL := os.Getenv("KAFKA_BOOTSTRAP_SERVERS")
	if kafkaURL == "" {
		kafkaURL = "localhost:9092" // Default
		log.Printf("KAFKA_BOOTSTRAP_SERVERS not set, defaulting to %s", kafkaURL)
	}

	// Initialize the Kafka Producer
	// This makes the producer available to the handlers package
	if err := kafka.InitKafkaProducer(kafkaURL); err != nil {
		log.Fatal(err)
	}
	pipeline := kafka.CurrentPipeline()
	receiver := handlers.NewReceiver(pipeline, kafka.Topic())

	router := receiverRouter(receiver, profile)

	// Run the server
	log.Printf("Starting Go AdTech Receiver on port %s...", port)
	server := &http.Server{Addr: ":" + port, Handler: router, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 30 * time.Second}
	stopped := make(chan os.Signal, 1)
	signal.Notify(stopped, syscall.SIGINT, syscall.SIGTERM)
	defer signal.Stop(stopped)
	serverErrors := make(chan error, 1)
	go func() { serverErrors <- server.ListenAndServe() }()
	select {
	case <-stopped:
	case err := <-serverErrors:
		if err != http.ErrServerClosed {
			log.Printf("Go HTTP server stopped: %v", err)
		}
	}
	pipeline.CloseIntake()
	before, _ := json.Marshal(pipeline.Snapshot())
	log.Printf("benchmark drain before: %s", before)
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(pipeline.Snapshot().DrainTimeoutMS)*time.Millisecond+5*time.Second)
	defer cancel()
	httpDone := make(chan struct{})
	go func() { _ = server.Shutdown(ctx); close(httpDone) }()
	drainErr := pipeline.Shutdown(ctx)
	select {
	case <-httpDone:
	case <-ctx.Done():
		_ = server.Close()
	}
	after, _ := json.Marshal(pipeline.Snapshot())
	log.Printf("benchmark drain after: %s", after)
	if drainErr != nil {
		log.Printf("Go pipeline drain failed: %v", drainErr)
		os.Exit(1)
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
