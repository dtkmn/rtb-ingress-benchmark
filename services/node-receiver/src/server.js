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
      shuttingDown = true;
      for (const worker of Object.values(cluster.workers)) {
        worker?.kill('SIGTERM');
      }
      process.exit(0);
    };

    process.on('SIGINT', stopCluster);
    process.on('SIGTERM', stopCluster);

    cluster.on('exit', (worker, code, signal) => {
      if (shuttingDown) {
        return;
      }
      console.error(
        'Worker %s exited (code=%s signal=%s); restarting',
        worker.process.pid,
        code,
        signal
      );
      cluster.fork();
    });
    return;
  }

  const server = await buildServer({ settings });
  try {
    await server.listen({ port, host });
  } catch (error) {
    server.log.error({ err: error }, 'Failed to start node-receiver');
    process.exit(1);
  }
}

main();
