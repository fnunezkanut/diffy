package com.github.fnunezkanut.bbcloud.external

import com.github.fnunezkanut.bbcloud.DiffyBuildInfo
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.origin
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.QueryStringDecoder
import org.jetbrains.ide.RestService
import org.jetbrains.io.response

/**
 * Reports the configured workspace slug to the Chrome extension at `POST /api/diffy/workspace`.
 *
 * The `GET /api/diffy/open` ping cannot do this on its own: Chrome omits the `Origin` header on GET requests from an
 * extension that holds host permissions for the target, so the ping cannot tell the diffy extension apart and withholds
 * the workspace. Chrome does send `Origin` on POST, so this endpoint answers the same body as [DiffyPingBody], with
 * `workspace` included only for [DiffyExternalOrigins.ALLOWED_EXTENSION_ORIGIN].
 */
class DiffyWorkspaceRestService : RestService() {
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
            LOG.debug("Origin check denied a workspace request: origin=${request.origin}, result=${result.name}")
        }
        return result
    }

    /** `workspaceSlugOrNull()` is a plain settings read, so it is safe on a Netty thread. */
    override fun execute(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): String? {
        val origin = request.origin
        val workspace = DiffyConfigurationService.getInstance().workspaceSlugOrNull()
        if (isDiffyExtensionOrigin(origin = origin)) {
            LOG.info("Answering the workspace request from the diffy extension: workspaceConfigured=${workspace != null}")
        } else {
            LOG.info(
                "Workspace request from origin $origin, expected ${DiffyExternalOrigins.ALLOWED_EXTENSION_ORIGIN}; " +
                    "the workspace is not disclosed"
            )
        }
        val body = DiffyPingBody.build(
            origin = origin,
            version = DiffyBuildInfo.version() ?: UNKNOWN_VERSION,
            workspace = workspace
        )
        val httpResponse = response(JSON_CONTENT_TYPE, Unpooled.copiedBuffer(body, Charsets.UTF_8))
        sendResponse(request, context, httpResponse)
        return null
    }

    companion object {
        private val LOG = logger<DiffyWorkspaceRestService>()

        private const val SERVICE_NAME = "diffy/workspace"
        private const val UNKNOWN_VERSION = "unknown"
        private const val JSON_CONTENT_TYPE = "application/json"
    }
}