package handlers

import (
	"bytes"
	"encoding/json"
	"errors"
	"github.com/dtkmn/go-adtech-receiver/internal/kafka"
	"github.com/dtkmn/go-adtech-receiver/internal/models"
	"github.com/gin-gonic/gin"
	"io"
	"net/http"
	"regexp"
	"strings"
	"time"
)

const preparationKey = "receiver.preparation.release"
const bodyKey = "receiver.preparation.body"

var jsonContentType = regexp.MustCompile(`(?i)^[ \t]*application/json[ \t]*(;[ \t]*charset[ \t]*=[ \t]*(utf-8|"utf-8")[ \t]*)?$`)

// Count all field occurrences, including noncanonical keys supplied by callers.
// net/http preserves repeated wire fields as separate values in a canonical key.
func validHeaders(headers http.Header) bool {
	var contentType, encoding []string
	for name, values := range headers {
		if strings.EqualFold(name, "Content-Type") {
			contentType = append(contentType, values...)
		}
		if strings.EqualFold(name, "Content-Encoding") {
			encoding = append(encoding, values...)
		}
	}
	return len(contentType) == 1 && asciiHeaderValue(contentType[0]) && jsonContentType.MatchString(contentType[0]) &&
		(len(encoding) == 0 || (len(encoding) == 1 && strings.EqualFold(strings.Trim(encoding[0], " \t"), "identity")))
}

func asciiHeaderValue(value string) bool {
	for i := range value {
		if value[i] >= 0x80 {
			return false
		}
	}
	return true
}

type Receiver struct {
	Pipeline    *kafka.Pipeline
	Topic       string
	Serialize   func(models.BidRequest) ([]byte, error)
	Conformance *kafka.Conformance
}

func NewReceiver(p *kafka.Pipeline, topic string) *Receiver {
	return &Receiver{Pipeline: p, Topic: topic, Conformance: p.Conformance(), Serialize: func(request models.BidRequest) ([]byte, error) { return json.Marshal(request) }}
}
func releasePreparation(c *gin.Context) {
	if value, ok := c.Get(preparationKey); ok {
		c.Request.Body = http.NoBody
		c.Set(bodyKey, nil)
		value.(func())()
	}
}
func (r *Receiver) prepareBody(c *gin.Context) bool {
	if !validHeaders(c.Request.Header) {
		c.AbortWithStatusJSON(http.StatusUnsupportedMediaType, gin.H{"status": "unsupported media"})
		return false
	}
	release, err := r.Pipeline.AcquirePreparation()
	if err != nil {
		c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{"status": "preparation unavailable"})
		return false
	}
	c.Set(preparationKey, release)
	original := c.Request.Body
	limited := http.MaxBytesReader(c.Writer, original, kafka.MaxBodyBytes)
	body, err := io.ReadAll(limited)
	original.Close()
	if err != nil {
		if _, ok := err.(*http.MaxBytesError); ok {
			c.AbortWithStatus(http.StatusRequestEntityTooLarge)
		} else {
			c.AbortWithStatusJSON(http.StatusBadRequest, gin.H{"status": "bad request"})
		}
		return false
	}
	c.Request.Body = io.NopCloser(bytes.NewReader(body))
	c.Set(bodyKey, body)
	return true
}

// Must precede middleware that reads the body (including size metrics).
func (r *Receiver) PreparationMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		if c.Request.Method != http.MethodPost || c.Request.URL.Path != "/bid-request" {
			c.Next()
			return
		}
		defer func() { releasePreparation(c); r.Pipeline.ObserveHTTP(c.Writer.Status()) }()
		if r.prepareBody(c) {
			c.Next()
		}
	}
}

// Temporary model/body/value leave scope and the preparation permit is released
// before confirm waits. The same strict validation precedes all mode filters.
func (r *Receiver) prepare(c *gin.Context) (receipt *kafka.Receipt, deadline time.Time, status int, message string) {
	var candidate *kafka.Candidate
	defer func() {
		if recover() != nil {
			if candidate != nil {
				candidate.Reject("preparation_panic")
			}
			receipt = nil
			deadline = time.Time{}
			status = http.StatusInternalServerError
			message = "serialization error"
		}
	}()
	body, _ := c.Get(bodyKey)
	request, err := models.DecodeBidRequest(body.([]byte))
	if err != nil {
		message := "bad request"
		if errors.Is(err, models.ErrInvalidJSON) {
			message = "invalid json payload"
		}
		return nil, time.Time{}, http.StatusBadRequest, message
	}
	if request.Device.LMT == 1 || (request.Device.IP != nil && strings.HasPrefix(*request.Device.IP, "10.10.")) {
		return nil, time.Time{}, http.StatusNoContent, ""
	}
	if r.Pipeline.Mode() == kafka.DeliveryModeHttpOnly {
		return nil, time.Time{}, http.StatusOK, ""
	}
	candidate = r.Pipeline.BeginCandidate(c.Request.Context())
	var value []byte
	if r.Conformance != nil {
		err = r.Conformance.SerializationCheck()
	}
	if err == nil {
		value, err = r.Serialize(request)
	}
	if err != nil {
		candidate.Reject("serialization")
		return nil, time.Time{}, http.StatusInternalServerError, "serialization error"
	}
	record := kafka.Record{Topic: r.Topic, Key: []byte(request.ID), Value: value}
	if r.Conformance != nil {
		receipt, err = r.Conformance.Admit(candidate, &record, func() func() {
			value, _ := c.Get(preparationKey)
			c.Set(preparationKey, func() {}) // Move the actual lease to pending work.
			return value.(func())
		})
	} else {
		receipt, err = candidate.Commit(record)
	}
	if err != nil {
		message := "kafka buffer full"
		if err == kafka.ErrUnavailable {
			message = "kafka unavailable"
		}
		return nil, time.Time{}, http.StatusServiceUnavailable, message
	}
	return receipt, candidate.Deadline(), http.StatusOK, ""
}
func (r *Receiver) ReceiveBid(c *gin.Context) {
	if _, prepared := c.Get(preparationKey); !prepared {
		defer func() { releasePreparation(c); r.Pipeline.ObserveHTTP(c.Writer.Status()) }()
		if !r.prepareBody(c) {
			return
		}
	}
	receipt, deadline, status, message := r.prepare(c)
	releasePreparation(c)
	if receipt != nil && r.Conformance != nil {
		r.Conformance.CallerReleased(receipt.AttemptID)
	}
	if status == http.StatusNoContent {
		c.Status(status)
		return
	}
	if status != http.StatusOK {
		if message == "invalid json payload" {
			c.JSON(status, gin.H{"error": message})
		} else {
			c.JSON(status, gin.H{"status": message})
		}
		return
	}
	if receipt != nil && r.Pipeline.Mode() == kafka.DeliveryModeConfirm {
		if err := receipt.Wait(c.Request.Context(), deadline); err != nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"status": "kafka buffer full"})
			return
		}
	}
	c.JSON(http.StatusOK, gin.H{"status": "accepted"})
}

// Kept for existing callers/tests. Production binds a stable Receiver instance.
func ReceiveBid(c *gin.Context) { NewReceiver(kafka.CurrentPipeline(), kafka.Topic()).ReceiveBid(c) }
