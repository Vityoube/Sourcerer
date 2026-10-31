package sourcerer

import dev.sourcerer.core.ApiException
import dev.sourcerer.core.Json
import dev.sourcerer.core.Principal
import dev.sourcerer.service.BrowseService
import dev.sourcerer.service.Platform
import groovy.transform.CompileStatic
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.grails.web.servlet.mvc.GrailsWebRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets

/**
 * Tiny, allocation-conscious HTTP helpers shared by all controllers. Responses are written as
 * raw bytes (no view resolution, no content negotiation, no reflection-based marshalling).
 */
@CompileStatic
final class Http {
    private static final Logger LOG = LoggerFactory.getLogger(Http)
    private static final int MAX_BODY = 1 << 20

    interface Action {
        void run() throws Exception
    }

    private Http() {}

    /** Runs the action and maps every failure to a JSON error. */
    static void run(HttpServletResponse res, Action action) {
        try {
            action.run()
        } catch (ApiException e) {
            error(res, e.status(), e.message)
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
            error(res, 400, 'malformed request: ' + e.class.simpleName)
        } catch (Exception e) {
            LOG.error('unhandled error', e)
            error(res, 500, 'internal error')
        } finally {
            // We write the bytes ourselves: tell Grails not to look for a view after the action returns.
            GrailsWebRequest w = GrailsWebRequest.lookup()
            if (w != null) w.renderView = false
        }
    }

    static void error(HttpServletResponse res, int status, String message) {
        if (res.committed) return
        send(res, status, Json.bytes([error: message]), null)
    }

    static void send(HttpServletResponse res, int status, byte[] body, String etag) {
        res.status = status
        res.contentType = 'application/json'
        res.characterEncoding = 'UTF-8'
        if (etag != null) {
            res.setHeader('ETag', etag)
            res.setHeader('Cache-Control', 'no-cache')
        }
        res.contentLength = body.length
        res.outputStream.write(body)
    }

    static void ok(HttpServletResponse res, Object value) {
        send(res, 200, Json.bytes(value), null)
    }

    static void created(HttpServletResponse res, Object value) {
        send(res, 201, Json.bytes(value), null)
    }

    /** Sends a cached body; answers 304 (no body) when the client's ETag still matches. */
    static void cached(HttpServletRequest req, HttpServletResponse res, BrowseService.Cached c) {
        if (c.etag() != null && c.etag() == req.getHeader('If-None-Match')) {
            res.status = 304
            res.setHeader('ETag', c.etag())
            return
        }
        send(res, 200, c.body(), c.etag())
    }

    /** Anonymous requests yield null; a presented-but-invalid credential is a 401. */
    static Principal principal(HttpServletRequest req, Platform platform) {
        String h = req.getHeader('Authorization')
        if (h == null) return null
        if (h.regionMatches(true, 0, 'Bearer ', 0, 7)) return platform.auth.fromBearer(h.substring(7).trim())
        if (h.regionMatches(true, 0, 'Basic ', 0, 6)) {
            String[] up = new String(Base64.decoder.decode(h.substring(6).trim()), StandardCharsets.UTF_8).split(':', 2)
            return platform.auth.fromBasic(up[0], up.length > 1 ? up[1] : '')
        }
        throw ApiException.unauthorized('unsupported authorization scheme')
    }

    static Principal requirePrincipal(HttpServletRequest req, Platform platform) {
        Principal p = principal(req, platform)
        if (p == null) throw ApiException.unauthorized('authentication required')
        p
    }

    static Map<String, Object> body(HttpServletRequest req) {
        byte[] raw = req.inputStream.readNBytes(MAX_BODY + 1)
        if (raw.length > MAX_BODY) throw new ApiException(413, 'request body too large')
        if (raw.length == 0) return [:]
        Json.MAPPER.readValue(raw, Map) as Map<String, Object>
    }

    static String str(Map<String, Object> m, String key) {
        Object v = m.get(key)
        v == null ? null : v.toString()
    }

    static int intParam(HttpServletRequest req, String name, int dflt) {
        String v = req.getParameter(name)
        if (v == null || v.isEmpty()) return dflt
        try { Integer.parseInt(v) } catch (NumberFormatException ignored) { throw ApiException.badRequest(name + ' must be an integer') }
    }

    static int number(String v) {
        try { Integer.parseInt(v) } catch (NumberFormatException ignored) { throw ApiException.notFound('not found') }
    }
}
