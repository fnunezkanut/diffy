package com.github.fnunezkanut.bbcloud.external

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
 * Receives the Bitbucket snapshot of the Chrome extension at `POST /api/diffy/snapshot`. Only
 * [DiffyExternalOrigins.ALLOWED_EXTENSION_ORIGIN] may send it. The body is parsed by [DiffyBrowserSnapshotParser], the
 * latest valid snapshot is kept by [DiffyBrowserSnapshotStore], and the reply has the same `status` / `message` shape as
 * `/api/diffy/open`. Bodies, titles and names are never logged, only counts.
 */
class DiffyBrowserSnapshotRestService : RestService() {
    override fun getServiceName(): String {
        return SERVICE_NAME
    }

    override fun isMethodSupported(method: HttpMethod): Boolean {
        return method == HttpMethod.POST
    }

    /** Same rule as the open endpoint: let any Chrome extension reach [execute], which holds the real origin gate. */
    override fun isOriginAllowed(request: HttpRequest): OriginCheckResult {
        if (isChromeExtensionOrigin(request = request)) {
            return OriginCheckResult.ALLOW
        }
        val result = super.isOriginAllowed(request)
        if (result != OriginCheckResult.ALLOW) {
            LOG.debug("Origin check denied a snapshot request: origin=${request.origin}, result=${result.name}")
        }
        return result
    }

    override fun execute(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): String? {
        val startedAt = System.nanoTime()
        val bytes = request.content().readableBytes()
        LOG.info(
            "Received ${request.method()} $SERVICE_PATH: origin=${request.origin}, bytes=$bytes, " +
                "contentType=${request.headers().get("Content-Type")}"
        )
        if (request.origin != DiffyExternalOrigins.ALLOWED_EXTENSION_ORIGIN) {
            LOG.info(
                "Rejected a snapshot from origin ${request.origin}; expected ${DiffyExternalOrigins.ALLOWED_EXTENSION_ORIGIN}"
            )
            sendJson(body = statusBody(status = STATUS_REJECTED, message = FORBIDDEN_ORIGIN), request = request, context = context)
            return null
        }

        try {
            handleSnapshot(request = request, context = context, bytes = bytes)
        } catch (exception: ProcessCanceledException) {
            throw exception
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            LOG.warn("Could not handle the snapshot request", exception)
            sendJson(body = statusBody(status = STATUS_REJECTED, message = PROCESSING_FAILED), request = request, context = context)
        }
        LOG.debug("Handled ${request.method()} $SERVICE_PATH in ${(System.nanoTime() - startedAt) / NANOS_PER_MILLI} ms")
        return null
    }

    private fun handleSnapshot(request: FullHttpRequest, context: ChannelHandlerContext, bytes: Int) {
        val body = request.content().toString(Charsets.UTF_8)
        when (val result = DiffyBrowserSnapshotParser.parse(body = body)) {
            is DiffyBrowserSnapshotParseResult.Rejected -> {
                LOG.info("Rejected a snapshot: ${result.message} (bytes=$bytes)")
                sendJson(body = statusBody(status = STATUS_REJECTED, message = result.message), request = request, context = context)
            }
            is DiffyBrowserSnapshotParseResult.Valid -> {
                val snapshot = result.snapshot
                DiffyBrowserSnapshotStore.getInstance().replace(snapshot = snapshot)
                LOG.info(
                    "Accepted a snapshot captured at ${snapshot.meta.capturedAt}: ${snapshot.pullRequests.size} pull requests, " +
                        "${snapshot.notificationEvents.size} events, ${snapshot.skippedInvalidEntries} skipped"
                )
                val message = "Stored ${snapshot.pullRequests.size} pull requests and " +
                    "${snapshot.notificationEvents.size} notification events."
                sendJson(body = statusBody(status = STATUS_ACCEPTED, message = message), request = request, context = context)
            }
        }
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
        private val LOG = logger<DiffyBrowserSnapshotRestService>()

        private const val SERVICE_NAME = "diffy/snapshot"
        private const val SERVICE_PATH = "/api/diffy/snapshot"
        private const val JSON_CONTENT_TYPE = "application/json"
        private const val STATUS_ACCEPTED = "accepted"
        private const val STATUS_REJECTED = "rejected"
        private const val PROCESSING_FAILED = "The snapshot could not be processed."
        private const val FORBIDDEN_ORIGIN = "This browser extension is not allowed to send snapshots."
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}