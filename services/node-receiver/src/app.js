'use strict';

const Fastify = require('fastify');

const { loadSettings } = require('./config');
const {
  PublishBackpressureError,
  PublishUnavailableError,
  createKafkaPublisher,
} = require('./publisher');
const { OwnedPipeline, PublishDeadlineError } = require('./pipeline');
const { validHeaders, parseReceiverBody } = require('./contract');
const { runtimeSnapshot } = require('./runtime');
const { FaultAdapter } = require('./conformance');

async function buildServer({
  settings = loadSettings(),
  publisher = undefined,
  bootstrapPublisher = true,
  logger = true,
  serialize = (value) => Buffer.from(JSON.stringify(pruneNullish(value))),
} = {}) {
  const logController = new Fastify.LogController({ disableRequestLogging: settings.telemetryProfile === 'controlled' });
  // Fastify's closing-intake diagnostic is otherwise emitted for every refused
  // request, bypassing disableRequestLogging. F2 drain snapshots remain logged.
  if (settings.telemetryProfile === 'controlled') logController.serviceUnavailable = () => {};
  const server = Fastify({
    logger,
    logController,
    bodyLimit: 65536,
    forceCloseConnections: true,
  });

  let runtimePublisher = publisher;
  let conformance = null;
  const pipeline = new OwnedPipeline({
    settings,
    available: () => conformance ? conformance.available() : runtimePublisher != null && (runtimePublisher.isAvailable?.() ?? true),
    dispatch: (record, id) => conformance ? conformance.dispatch(record, id) : runtimePublisher.publish({ ...record, confirm: true }),
    teardown: () => conformance ? conformance.teardown() : runtimePublisher?.stop(),
  });
  if (settings.conformanceEnabled) conformance = new FaultAdapter(pipeline, settings);
  server.decorate('pipeline', pipeline);
  server.decorateRequest('preparationRelease', null);
  const releasePreparation = (request) => { request.preparationRelease?.(); request.preparationRelease = null; };
  server.removeAllContentTypeParsers();
  server.addContentTypeParser('*', { parseAs: 'buffer' }, (request, bytes, done) => {
    try {
      done(null, request.raw.url.split('?')[0] === '/benchmark/conformance' ? JSON.parse(bytes.toString('utf8')) : parseReceiverBody(bytes));
    } catch (error) { done(error); }
  });
  server.addHook('onRequest', async (request, reply) => {
    if (request.raw.url.split('?')[0] === '/benchmark/conformance') {
      if (!conformance || !conformance.authorized(request.headers)) reply.code(404).send();
      return;
    }
    if (request.raw.url.split('?')[0] !== '/bid-request') return;
    if (!validHeaders(request.raw.rawHeaders)) { reply.code(415).send({ status: 'unsupported media' }); return; }
    request.preparationRelease = pipeline.acquirePreparation();
    if (!request.preparationRelease) { reply.code(503).send({ status: 'receiver busy' }); return; }
  });
  server.addHook('onResponse', async (request, reply) => {
    releasePreparation(request);
    if (request.raw.url.split('?')[0] === '/bid-request') pipeline.httpStatuses[reply.statusCode] = (pipeline.httpStatuses[reply.statusCode] || 0) + 1;
  });
  server.addHook('onError', async (request) => { releasePreparation(request); });

  let diagnosticWindow = 0, diagnosticCount = 0;
  const boundedDiagnostic = () => {
    const now = performance.now();
    if (now - diagnosticWindow >= 60000) { diagnosticWindow = now; diagnosticCount = 0; }
    if (diagnosticCount >= 3) return false;
    diagnosticCount++;
    return true;
  };

  server.setErrorHandler((error, request, reply) => {
    releasePreparation(request);
    if (error?.statusCode === 400) {
      reply.code(400).send({ status: 'bad request' });
      return;
    }

    if (settings.telemetryProfile === 'operational') request.log.error({ err: error }, 'Unhandled request error');
    else if ((error?.statusCode ?? 500) >= 500 && boundedDiagnostic()) request.log.error({ err: { name: error?.name, message: String(error?.message).slice(0, 512) } }, 'Receiver error; aggregate outcome retained');
    reply.send(error);
  });

  server.addHook('onReady', async () => {
    if (conformance) { server.log.info('Token-gated conformance transport enabled; no Kafka SDK producer constructed'); return; }
    if (runtimePublisher == null && bootstrapPublisher && settings.usesKafka()) {
      runtimePublisher = createKafkaPublisher(settings, server.log);
    }

    if (runtimePublisher != null && settings.usesKafka()) {
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

  server.addHook('preClose', async () => {
    if (!settings.usesKafka()) runtimePublisher = null;
    try { await pipeline.stop(); }
    finally { server.log.info({ benchmark_state: pipeline.snapshot() }, 'Receiver drain finished'); }
  });

  server.get('/health', async () => ({ status: 'healthy' }));
  server.get('/benchmark/state', async () => pipeline.snapshot());
  server.get('/benchmark/runtime', async () => runtimeSnapshot(server, settings, runtimePublisher, logController));
  server.get('/benchmark/conformance', async () => conformance.snapshot());
  server.post('/benchmark/conformance', async (request, reply) => {
    try { return await conformance.command(request.body); }
    catch (error) { reply.code(409).send({ error: String(error.message).slice(0, 512) }); }
  });

  server.post('/bid-request', async (request, reply) => {
    let bidRequest = request.body;
    let payload;
    let candidate;
    try {

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

    candidate = pipeline.beginCandidate();
    try {
      conformance?.serializationCheck();
      payload = serialize(bidRequest);
    } catch {
      pipeline.reject(candidate, 'serialization');
      reply.code(500).send({ status: 'serialization error' });
      return;
    }

    try {
      let record = {
        topic: settings.kafkaTopic,
        payload,
        key: Buffer.from(bidRequest.id, 'utf8'),
      };
      const receipt = conformance ? await conformance.admit(candidate, record, () => {
        const release = request.preparationRelease;
        request.preparationRelease = null;
        return release;
      }) : pipeline.admit(candidate, record);
      request.body = null;
      bidRequest = null;
      payload = null;
      record = null;
      releasePreparation(request);
      conformance?.callerReleased(receipt.id);
      if (settings.isConfirm()) await pipeline.confirm(receipt);
    } catch (error) {
      if (error instanceof PublishBackpressureError) {
        reply.code(503).send({ status: 'kafka buffer full' });
        return;
      }
      if (error instanceof PublishUnavailableError || error instanceof PublishDeadlineError) {
        reply.code(503).send({ status: 'kafka unavailable' });
        return;
      }
      throw error;
    }

    return { status: 'accepted' };
    } finally {
      if (candidate?.pending) pipeline.reject(candidate, 'internal_preparation');
      request.body = null;
      bidRequest = null;
      payload = null;
      releasePreparation(request);
    }
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
