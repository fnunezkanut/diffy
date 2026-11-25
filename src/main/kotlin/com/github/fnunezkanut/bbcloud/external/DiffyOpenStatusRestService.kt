package com.github.fnunezkanut.bbcloud.external

import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
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
 * Tells the browser extension how an open of a pull request is going, at `POST /api/diffy/open-status`. The extension
 * polls it after `/api/diffy/open` accepted a request, so it can show a loading icon until the IDE window has opened.
 *
 * The body is the same `workspace`, `repo` and `pr` object as the open request and is validated by
 * [DiffyOpenRequestValidator]. The reply is `{"status": "opening" | "opened" | "failed" | "unknown", "message": ...}`,
 * or `{"status": "rejected", ...}` for a bad request or an origin that is not a Diffy extension. Only
 * [DiffyOpenProgressTracker] is read, which is a map lookup, so this is safe on a Netty I/O thread.
 */
class DiffyOpenStatusRestService : RestService() {
    override fun getServiceName(): String {
        return SERVICE_NAME
    }

    override fun isMethodSupported(method: HttpMethod): Boolean {
        return method == HttpMethod.POST
    }

    /** Same rule as the open endpoint: let any browser extension reach [execute], which holds the real origin gate. */
    override fun isOriginAllowed(request: HttpRequest): OriginCheckResult {
        if (isBrowserExtensionOrigin(request = request)) {
            return OriginCheckResult.ALLOW
        }
        val result = super.isOriginAllowed(request)
        if (result != OriginCheckResult.ALLOW) {
            LOG.debug("Origin check denied an open status request: origin=${request.origin}, result=${result.name}")
        }
        return result
    }

    override fun execute(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): String? {
        try {
            sendJson(body = answer(request = request), request = request, context = context)
        } catch (exception: ProcessCanceledException) {
            throw exception
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            LOG.warn("Could not handle the open status request", exception)
            sendJson(body = statusBody(status = STATUS_REJECTED, message = PROCESSING_FAILED), request = request, context = context)
        }
        return null
    }

    private fun answer(request: FullHttpRequest): String {
        if (!isDiffyExtensionOrigin(origin = request.origin)) {
            LOG.info("Rejected an open status request from origin ${request.origin}")
            return statusBody(status = STATUS_REJECTED, message = FORBIDDEN_ORIGIN)
        }
        val body = request.content().toString(Charsets.UTF_8)
        val configuredWorkspace = DiffyConfigurationService.getInstance().workspaceSlugOrNull()
        return when (val validation = DiffyOpenRequestValidator.validate(body = body, configuredWorkspace = configuredWorkspace)) {
            is DiffyOpenRequestValidation.Rejected -> {
                statusBody(status = STATUS_REJECTED, message = validation.message)
            }
            is DiffyOpenRequestValidation.Valid -> {
                progressBody(request = validation.request)
            }
        }
    }

    private fun progressBody(request: DiffyOpenRequest): String {
        val progress = DiffyOpenProgressTracker.getInstance().progressFor(
            workspace = request.workspace,
            repoSlug = request.repoSlug,
            prNumber = request.prNumber
        )
        val status = when (progress?.state) {
            DiffyOpenProgressState.OPENING -> STATUS_OPENING
            DiffyOpenProgressState.OPENED -> STATUS_OPENED
            DiffyOpenProgressState.FAILED -> STATUS_FAILED
            null -> STATUS_UNKNOWN
        }
        LOG.debug("Answering the open status of pull request #${request.prNumber}: status=$status")
        return statusBody(status = status, message = progress?.message)
    }

    private fun statusBody(status: String, message: String?): String {
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
        private val LOG = logger<DiffyOpenStatusRestService>()

        private const val SERVICE_NAME = "diffy/open-status"
        private const val JSON_CONTENT_TYPE = "application/json"
        private const val STATUS_OPENING = "opening"
        private const val STATUS_OPENED = "opened"
        private const val STATUS_FAILED = "failed"
        private const val STATUS_UNKNOWN = "unknown"
        private const val STATUS_REJECTED = "rejected"
        private const val PROCESSING_FAILED = "The request could not be processed."
        private const val FORBIDDEN_ORIGIN = "This browser extension is not allowed to ask for the open status."
    }
}