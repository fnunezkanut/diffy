package com.github.fnunezkanut.bbcloud.external

import com.github.fnunezkanut.bbcloud.DiffyBuildInfo
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.ui.worktree.PullRequestWorktreeOpener
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.util.io.origin
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.QueryStringDecoder
import org.jetbrains.ide.RestService
import org.jetbrains.io.response
import kotlin.coroutines.cancellation.CancellationException

/**
 * The browser entry point of the "Open in IntelliJ" feature, served at `/api/diffy/open`.
 *
 * - `GET` is the port-discovery ping. It answers `{"plugin":"diffy","version":...}`. Only for
 *   [DiffyExternalOrigins.ALLOWED_EXTENSION_ORIGIN] it adds the configured `workspace` slug (or null); see [DiffyPingBody].
 *   Chrome omits `Origin` on this GET from the extension, so the extension reads the workspace from
 *   [DiffyWorkspaceRestService] instead.
 * - `POST` opens a pull request. Only [DiffyExternalOrigins.ALLOWED_EXTENSION_ORIGIN] may send it. The body is
 *   validated, the reply is `accepted` or `rejected`, and the open runs in the background.
 *
 * Requests run on a Netty I/O thread, so this class does no git, network or project work itself.
 */
class DiffyOpenPullRequestRestService : RestService() {
    override fun getServiceName(): String {
        return SERVICE_NAME
    }

    override fun isMethodSupported(method: HttpMethod): Boolean {
        return method == HttpMethod.GET || method == HttpMethod.POST
    }

    /**
     * Lets requests from any Chrome or Firefox extension reach [execute]. Without this, the platform's accessibility check drops a
     * request from an unknown extension and answers 404, and the extension cannot tell why it failed. [execute] keeps the
     * real gate: it answers the ping for any extension, and it accepts a POST only from
     * [DiffyExternalOrigins.ALLOWED_EXTENSION_ORIGIN]. Other origins keep the platform's default handling.
     */
    override fun isOriginAllowed(request: HttpRequest): OriginCheckResult {
        if (isBrowserExtensionOrigin(request = request)) {
            return OriginCheckResult.ALLOW
        }
        val result = super.isOriginAllowed(request)
        if (result != OriginCheckResult.ALLOW) {
            LOG.debug("Origin check denied an open request: origin=${request.origin}, result=${result.name}")
        }
        return result
    }

    override fun execute(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): String? {
        val startedAt = System.nanoTime()
        try {
            if (request.method() == HttpMethod.GET) {
                handlePing(request = request, context = context)
            } else {
                handleOpen(request = request, context = context)
            }
        } catch (exception: ProcessCanceledException) {
            throw exception
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            LOG.warn("Could not handle the ${request.method()} request", exception)
            sendJson(body = statusBody(status = STATUS_REJECTED, message = PROCESSING_FAILED), request = request, context = context)
        }
        LOG.debug("Handled ${request.method()} $SERVICE_PATH in ${(System.nanoTime() - startedAt) / NANOS_PER_MILLI} ms")
        return null
    }

    private fun handlePing(request: FullHttpRequest, context: ChannelHandlerContext) {
        val origin = request.origin
        val workspaceConfigured = DiffyConfigurationService.getInstance().workspaceSlugOrNull() != null
        if (isDiffyExtensionOrigin(origin = origin)) {
            LOG.info(
                "Answering the diffy ping from the diffy extension: origin=$origin, " +
                    "workspaceConfigured=$workspaceConfigured, version=${DiffyBuildInfo.version()}"
            )
        } else if (isBrowserExtensionOrigin(origin = origin)) {
            LOG.info(
                "Ping from a browser extension that is not the diffy extension: origin=$origin, expected " +
                    "${DiffyExternalOrigins.ALLOWED_EXTENSION_ORIGIN}; the workspace is not disclosed"
            )
        } else {
            LOG.debug("Answering the diffy ping: origin=$origin")
        }
        sendJson(body = pingBody(origin = origin), request = request, context = context)
    }

    private fun handleOpen(request: FullHttpRequest, context: ChannelHandlerContext) {
        if (!isDiffyExtensionOrigin(origin = request.origin)) {
            LOG.info(
                "Rejected an open request from origin ${request.origin}; expected ${DiffyExternalOrigins.ALLOWED_EXTENSION_ORIGIN}"
            )
            sendJson(body = statusBody(status = STATUS_REJECTED, message = FORBIDDEN_ORIGIN), request = request, context = context)
            return
        }

        val body = request.content().toString(Charsets.UTF_8)
        val configuredWorkspace = DiffyConfigurationService.getInstance().workspaceSlugOrNull()
        when (val validation = DiffyOpenRequestValidator.validate(body = body, configuredWorkspace = configuredWorkspace)) {
            is DiffyOpenRequestValidation.Rejected -> {
                LOG.info("Rejected an open request: ${validation.message}")
                sendJson(body = statusBody(status = STATUS_REJECTED, message = validation.message), request = request, context = context)
            }
            is DiffyOpenRequestValidation.Valid -> {
                val open = validation.request
                LOG.info("Accepted an open request for pull request #${open.prNumber}")
                PullRequestWorktreeOpener.getInstance().openInBackground(
                    workspace = open.workspace,
                    repoSlug = open.repoSlug,
                    prNumber = open.prNumber
                )
                val message = "Opening pull request #${open.prNumber} in IntelliJ."
                sendJson(body = statusBody(status = STATUS_ACCEPTED, message = message), request = request, context = context)
            }
        }
    }

    /** `workspaceSlugOrNull()` is a plain settings read, so it is safe on a Netty thread. */
    private fun pingBody(origin: String?): String {
        return DiffyPingBody.build(
            origin = origin,
            version = DiffyBuildInfo.version() ?: UNKNOWN_VERSION,
            workspace = DiffyConfigurationService.getInstance().workspaceSlugOrNull()
        )
    }

    private fun statusBody(status: String, message: String): String {
        val body = JsonObject()
        body.addProperty("status", status)
        body.addProperty("message", message)
        return body.toString()
    }

    private fun sendJson(body: String, request: FullHttpRequest, context: ChannelHandlerContext) {
        val httpResponse = response(JSON_CONTENT_TYPE, Unpooled.copiedBuffer(body, Charsets.UTF_8))
        sendResponse(request, context, httpResponse)
    }

    companion object {
        private val LOG = logger<DiffyOpenPullRequestRestService>()

        private const val SERVICE_NAME = "diffy/open"
        private const val SERVICE_PATH = "/api/diffy/open"
        private const val UNKNOWN_VERSION = "unknown"
        private const val JSON_CONTENT_TYPE = "application/json"
        private const val STATUS_ACCEPTED = "accepted"
        private const val STATUS_REJECTED = "rejected"
        private const val PROCESSING_FAILED = "The request could not be processed."
        private const val FORBIDDEN_ORIGIN = "This browser extension is not allowed to open pull requests."
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}