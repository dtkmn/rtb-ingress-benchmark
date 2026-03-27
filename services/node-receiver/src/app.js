'use strict';

const Fastify = require('fastify');

const { loadSettings } = require('./config');
const {
  PublishBackpressureError,
  PublishUnavailableError,
  createKafkaPublisher,
} = require('./publisher');

async function buildServer({
  settings = loadSettings(),
  publisher = undefined,
  bootstrapPublisher = true,
} = {}) {
  const server = Fastify({
    logger: true,
  });

  let runtimePublisher = publisher;

  server.setErrorHandler((error, request, reply) => {
    if (error?.statusCode === 400) {
      reply.code(400).send({ status: 'bad request' });
      return;
    }

    request.log.error({ err: error }, 'Unhandled request error');
    reply.send(error);
  });

  server.addHook('onReady', async () => {
    if (runtimePublisher == null && bootstrapPublisher && settings.usesKafka()) {
      runtimePublisher = createKafkaPublisher(settings, server.log);
    }

    if (runtimePublisher != null) {
      await runtimePublisher.start();
      server.log.info(
        {
          deliveryMode: settings.deliveryMode,
          acks: settings.kafkaAcks,
        },
        'Initialized Node receiver publisher'
      );
      return;
    }

    server.log.info('HTTP-only benchmark mode enabled; skipping Kafka producer initialization');
  });

  server.addHook('onClose', async () => {
    if (runtimePublisher != null) {
      await runtimePublisher.stop();
    }
  });

  server.get('/health', async () => ({ status: 'healthy' }));

  server.post('/bid-request', async (request, reply) => {
    const bidRequest = request.body;

    if (
      !bidRequest?.id ||
      bidRequest.device == null ||
      (bidRequest.site == null && bidRequest.app == null)
    ) {
      reply.code(400).send({ status: 'bad request' });
      return;
    }

    if (bidRequest.device.lmt === 1) {
      reply.code(204).send();
      return;
    }

    if (typeof bidRequest.device.ip === 'string' && bidRequest.device.ip.startsWith('10.10.')) {
      reply.code(204).send();
      return;
    }

    if (settings.isHttpOnly()) {
      return { status: 'accepted' };
    }

    let payload;
    try {
      payload = Buffer.from(JSON.stringify(pruneNullish(bidRequest)));
    } catch {
      reply.code(500).send({ status: 'serialization error' });
      return;
    }

    if (runtimePublisher == null) {
      reply.code(503).send({ status: 'kafka unavailable' });
      return;
    }

    try {
      await runtimePublisher.publish({
        topic: settings.kafkaTopic,
        payload,
        key: bidRequest.id,
        confirm: settings.isConfirm(),
      });
    } catch (error) {
      if (error instanceof PublishBackpressureError) {
        reply.code(503).send({ status: 'kafka buffer full' });
        return;
      }
      if (error instanceof PublishUnavailableError) {
        reply.code(503).send({ status: 'kafka unavailable' });
        return;
      }
      throw error;
    }

    return { status: 'accepted' };
  });

  return server;
}

function pruneNullish(value) {
  if (Array.isArray(value)) {
    return value.map(pruneNullish);
  }

  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value)
        .filter(([, entryValue]) => entryValue !== null && entryValue !== undefined)
        .map(([key, entryValue]) => [key, pruneNullish(entryValue)])
    );
  }

  return value;
}

module.exports = {
  buildServer,
  pruneNullish,
};

