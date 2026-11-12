'use strict';

const cluster = require('node:cluster');

const { buildServer } = require('./app');
const { loadSettings } = require('./config');

async function main() {
  const settings = loadSettings();
  const port = Number.parseInt(process.env.PORT || '8080', 10);
  const host = process.env.HOST || '0.0.0.0';

  if (cluster.isPrimary && settings.httpServerWorkers > 1) {
    let shuttingDown = false;
    console.log(
      'Starting node-receiver cluster with %d workers (delivery_mode=%s, acks=%s)',
      settings.httpServerWorkers,
      settings.deliveryMode,
      settings.kafkaAcks
    );
    for (let index = 0; index < settings.httpServerWorkers; index += 1) {
      cluster.fork();
    }

    const stopCluster = () => {
      if (shuttingDown) return;
      shuttingDown = true;
      for (const worker of Object.values(cluster.workers)) {
        worker?.kill('SIGTERM');
      }
      // Keep the primary alive until workers finish their drain. Previously
      // it exited immediately after signalling and hid child teardown.
      const deadline = setTimeout(() => {
        console.error('Node receiver workers did not finish drain within grace');
        for (const worker of Object.values(cluster.workers)) worker?.kill('SIGKILL');
        process.exit(1);
      }, settings.drainTimeoutMs + 7000);
      const check = () => {
        if (Object.keys(cluster.workers).length === 0) { clearTimeout(deadline); process.exit(clusterExitCode); }
      };
      cluster.on('exit', check);
      check();
    };
    let clusterExitCode = 0;

    process.on('SIGINT', stopCluster);
    process.on('SIGTERM', stopCluster);

    cluster.on('exit', (worker, code, signal) => {
      if (shuttingDown) {
        if (code !== 0 || signal) clusterExitCode = 1;
        return;
      }
      console.error(
        'Worker %s exited unexpectedly (code=%s signal=%s); failing receiver',
        worker.process.pid,
        code,
        signal
      );
      clusterExitCode = 1;
      stopCluster();
    });
    return;
  }

  const server = await buildServer({ settings });
  installShutdown(server, settings);
  try {
    await server.listen({ port, host });
  } catch (error) {
    server.log.error({ err: error }, 'Failed to start node-receiver');
    process.exit(1);
  }
}

function installShutdown(server, settings) {
  let stopping = false;
  const stop = async () => {
    if (stopping) return;
    stopping = true;
    server.pipeline.closeIntake();
    const watchdog = setTimeout(() => {
      server.log.error({ benchmark_state: { ...server.pipeline.snapshot(), drain_result: 'shutdown_grace_exceeded' } }, 'Receiver shutdown exceeded grace; outstanding work is unconfirmed');
      process.exit(1);
    }, settings.drainTimeoutMs + 6000);
    try {
      await server.close();
      const state = server.pipeline.snapshot();
      clearTimeout(watchdog);
      process.exit(state.drain_result === 'settled' ? 0 : 1);
    } catch (error) {
      server.log.error({ err: error, benchmark_state: server.pipeline.snapshot() }, 'Receiver shutdown failed');
      clearTimeout(watchdog);
      process.exit(1);
    }
  };
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
}

if (require.main === module) main().catch((error) => { console.error(error); process.exit(1); });
module.exports = { installShutdown };
