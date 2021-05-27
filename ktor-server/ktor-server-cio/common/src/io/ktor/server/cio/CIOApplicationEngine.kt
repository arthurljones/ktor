/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.server.cio

import io.ktor.application.*
import io.ktor.http.cio.*
import io.ktor.server.cio.backend.*
import io.ktor.server.engine.*
import io.ktor.test.dispatcher.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.atomicfu.*
import kotlinx.coroutines.*

/**
 * Engine that based on CIO backend
 */
public class CIOApplicationEngine(
    environment: ApplicationEngineEnvironment,
    configure: Configuration.() -> Unit
) : BaseApplicationEngine(environment, initEngine = false) {

    /**
     * CIO-based server configuration
     */
    public class Configuration : BaseApplicationEngine.Configuration() {
        /**
         * Number of seconds that the server will keep HTTP IDLE connections open.
         * A connection is IDLE if there are no active requests running.
         */
        public var connectionIdleTimeoutSeconds: Int = 45
    }

    private val configuration = Configuration().apply(configure)

    private val dispatcher = CIODispatcher(configuration, environment.connectors.size)

    private val engineDispatcher get() = dispatcher.engineDispatcher

    private val userDispatcher get() = dispatcher.userDispatcher

    private val startupJob: CompletableDeferred<Unit> = CompletableDeferred()
    private val stopRequest: CompletableJob = Job()

    private var serverJob by atomic<Job?>(null)

    init {
        initEngine()
    }

    override fun start(wait: Boolean): ApplicationEngine {
        serverJob = CoroutineScope(environment.parentCoroutineContext + engineDispatcher).launch {
            val connectors = ArrayList<HttpServer>(environment.connectors.size)
            try {
                environment.connectors.forEach { connectorSpec ->
                    if (connectorSpec.type == ConnectorType.HTTPS) {
                        throw UnsupportedOperationException(
                            "CIO Engine does not currently support HTTPS. Please " +
                                "consider using a different engine if you require HTTPS"
                        )
                    }
                }
                withContext(userDispatcher) {
                    environment.start()
                }
                environment.connectors.forEach { connectorSpec ->
                    val connector = startConnector(connectorSpec.host, connectorSpec.port)
                    connectors.add(connector)
                }

                connectors.map { it.serverSocket }.awaitAll()
            } catch (cause: Throwable) {
                connectors.forEach { it.rootServerJob.cancel() }
                stopRequest.completeExceptionally(cause)
                startupJob.completeExceptionally(cause)
                throw cause
            }

            startupJob.complete(Unit)
            stopRequest.join()

            // stopping
            connectors.forEach { it.acceptJob.cancel() }

            withContext(userDispatcher) {
                environment.monitor.raise(ApplicationStopPreparing, environment)
            }
        }.apply {
            invokeOnCompletion { cause ->
                cause?.let { stopRequest.completeExceptionally(cause) }
                cause?.let { startupJob.completeExceptionally(cause) }
                try {
                    environment.stop()
                } finally {
                    dispatcher.completeShutdown()
                }
            }
        }

        testSuspend {
            startupJob.await()

            if (wait) {
                serverJob!!.join()
            }
        }

        return this
    }

    override fun stop(gracePeriodMillis: Long, timeoutMillis: Long) {
        try {
            shutdownServer(gracePeriodMillis, timeoutMillis)
        } finally {
            GlobalScope.launch(engineDispatcher) {
                dispatcher.close()
            }
        }
    }

    private fun shutdownServer(gracePeriodMillis: Long, timeoutMillis: Long) {
        stopRequest.complete()

        testSuspend {
            val result = withTimeoutOrNull(gracePeriodMillis) {
                serverJob?.join()
                true
            }

            if (result == null) {
                // timeout
                dispatcher.prepareShutdown()
                serverJob?.cancel()

                val forceShutdown = withTimeoutOrNull(timeoutMillis - gracePeriodMillis) {
                    serverJob?.join()
                    false
                } ?: true

                if (forceShutdown) {
                    environment.stop()
                }
            }
        }
    }

    private fun CoroutineScope.startConnector(host: String, port: Int): HttpServer {
        val settings = HttpServerSettings(
            host = host,
            port = port,
            connectionIdleTimeoutSeconds = configuration.connectionIdleTimeoutSeconds.toLong()
        )

        return httpServer(settings) { request ->
            handleRequest(request)
        }
    }

    private suspend fun ServerRequestScope.handleRequest(request: Request) {
        withContext(userDispatcher) {
            val call = CIOApplicationCall(
                application,
                request,
                input,
                output,
                engineDispatcher,
                userDispatcher,
                upgraded,
                remoteAddress,
                localAddress
            )

            try {
                pipeline.execute(call)
            } catch (error: Exception) {
                handleFailure(call, error)
            } finally {
                call.release()
            }
        }
    }
}
