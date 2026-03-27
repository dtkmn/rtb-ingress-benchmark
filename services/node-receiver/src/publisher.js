'use strict';

const { Kafka } = require('kafkajs');

class PublishUnavailableError extends Error {
  constructor(cause) {
    super('Kafka unavailable');
    this.name = 'PublishUnavailableError';
    this.cause = cause;
  }
}

class PublishBackpressureError extends Error {
  constructor(cause) {
    super('Kafka backpressure');
    this.name = 'PublishBackpressureError';
    this.cause = cause;
  }
}

function createKafkaPublisher(settings, logger = console) {
  const kafka = new Kafka({
    clientId: 'node-receiver',
    brokers: settings.kafkaBootstrapServers.split(',').map((broker) => broker.trim()).filter(Boolean),
    connectionTimeout: settings.kafkaRequestTimeoutMs,
    requestTimeout: settings.kafkaRequestTimeoutMs,
  });

  const producer = kafka.producer({
    allowAutoTopicCreation: false,
    retry: {
      retries: settings.kafkaRetries,
      initialRetryTime: settings.kafkaRetryBackoffMs,
    },
    ...(settings.isConfirm() ? { maxInFlightRequests: 1 } : {}),
  });

  return {
    async start() {
      await producer.connect();
    },
    async stop() {
      await producer.disconnect();
    },
    async publish({ topic, payload, key, confirm }) {
      const operation = producer.send({
        topic,
        messages: [
          {
            key,
            value: payload,
          },
        ],
        acks: settings.kafkaAcks,
        timeout: settings.kafkaRequestTimeoutMs,
      });

      if (!confirm) {
        operation.catch((error) => {
          logger.warn({ err: error }, 'Kafka enqueue failed after HTTP response');
        });
        return;
      }

      try {
        await operation;
      } catch (error) {
        throw classifyKafkaError(error);
      }
    },
  };
}

function classifyKafkaError(error) {
  const message = String(error?.message || '').toLowerCase();
  if (
    message.includes('timeout') ||
    message.includes('queue') ||
    message.includes('request') ||
    message.includes('not enough replicas')
  ) {
    return new PublishBackpressureError(error);
  }
  return new PublishUnavailableError(error);
}

module.exports = {
  PublishUnavailableError,
  PublishBackpressureError,
  createKafkaPublisher,
};
