/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import java.io.*

/**
 * This plugin provides a single page application
 */
public class SinglePageApplication internal constructor(configuration: Configuration) {

    internal val defaultPage: String = configuration.defaultPage

    internal val applicationRoute: String = configuration.applicationRoute

    internal val filesPath: String = configuration.filesPath

    internal val ignoredFiles: MutableList<(String) -> Boolean> = configuration.ignoredFiles

    internal val usePackageNames: Boolean = configuration.useResources

    /**
     * Configuration type for the [SinglePageApplication] plugin
     */
    public class Configuration(
        /**
         * Default name of file or resource to serve when [applicationRoute] is requested
         */
        public var defaultPage: String = "index.html",

        /**
         * The URL path under which the content should be served
         */
        public var applicationRoute: String = "/",

        /**
         * Path under which the static content is located.
         * Corresponds to the folder path if the [useResources] is true, resource path otherwise
         */
        public var filesPath: String = "",

        /**
         * Specifies if static content is a resource or folder
         */
        public var useResources: Boolean = false,

        /**
         * List of callbacks checking if file or resource in [filesPath] is ignored
         * Request for such files or resources fails with 404 Forbidden status
         */
        internal val ignoredFiles: MutableList<(String) -> Boolean> = mutableListOf()
    ) {
        /**
         * Register a [block] in [ignoredFiles]
         * [block] returns true if [path] should be ignored.
         */
        public fun ignoreFiles(block: (path: String) -> Boolean) {
            ignoredFiles += block
        }

        /**
         *
         */
        public fun angular(filesPath: String) {
            this.filesPath = filesPath
        }

        /**
         *
         */
        public fun react(filesPath: String) {
            this.filesPath = filesPath
        }

        /**
         *
         */
        public fun vue(filesPath: String) {
            this.filesPath = filesPath
        }

        /**
         *
         */
        public fun ember(filesPath: String) {
            this.filesPath = filesPath
        }

        /**
         *
         */
        public fun backbone(filesPath: String) {
            this.filesPath = filesPath
        }
    }

    /**
     * Implementation of [ApplicationPlugin] for the [SinglePageApplication]
     */
    public companion object Plugin : ApplicationPlugin<Application, Configuration, SinglePageApplication> {
        override val key: AttributeKey<SinglePageApplication> = AttributeKey("SinglePage")

        override fun install(pipeline: Application, configure: Configuration.() -> Unit): SinglePageApplication {
            val plugin = SinglePageApplication(Configuration().apply(configure))

            pipeline.routing {
                static(plugin.applicationRoute) {
                    if (plugin.usePackageNames) {
                        resources(plugin.filesPath)
                        defaultResource(plugin.defaultPage, plugin.filesPath)
                    } else {
                        staticRootFolder = File(plugin.filesPath)
                        files(".")
                        default(plugin.defaultPage)
                    }
                }
            }

            pipeline.sendPipeline.intercept(ApplicationSendPipeline.Before) {
                plugin.interceptCall(this)
            }
            return plugin
        }
    }

    private suspend fun interceptCall(
        context: PipelineContext<Any, ApplicationCall>,
    ) = with(context) context@{
        val call = context.call
        val requestUrl = call.request.uri

        if (call.attributes.contains(key)) return@context

        if (!isUriStartWith(requestUrl)) return@context

        call.attributes.put(key, this@SinglePageApplication)

        if (ignoredFiles.firstOrNull { it.invoke(requestUrl) } != null) {
            call.response.status(HttpStatusCode.NotFound)
            call.respondText("File with path $requestUrl is in ignore list")
            finish()
        }
    }

    private fun isUriStartWith(uri: String) =
        uri.startsWith(applicationRoute) || uri.startsWith("/$applicationRoute")
}
