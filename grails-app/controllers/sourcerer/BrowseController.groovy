package sourcerer

import dev.sourcerer.core.Principal
import dev.sourcerer.service.Platform
import groovy.transform.CompileStatic

/** File browsing. {@code ref} and {@code path} are query parameters so branch names may contain '/'. */
@CompileStatic
class BrowseController {
    Platform platform

    def tree() {
        Http.run(response) {
            Http.cached(request, response, platform.browse.tree(Http.principal(request, platform),
                    params.org as String, params.repo as String, request.getParameter('ref'),
                    request.getParameter('path')))
        }
    }

    def blob() {
        Http.run(response) {
            Http.cached(request, response, platform.browse.blob(Http.principal(request, platform),
                    params.org as String, params.repo as String, request.getParameter('ref'),
                    request.getParameter('path')))
        }
    }

    /** Raw bytes of a file (images, binaries, large text). */
    def raw() {
        Http.run(response) {
            Principal who = Http.principal(request, platform)
            String owner = params.org as String
            String name = params.repo as String
            String sha = platform.browse.resolveForRaw(who, owner, name, request.getParameter('ref'))
            String etag = '"' + sha + '"'
            if (etag == request.getHeader('If-None-Match')) {
                response.status = 304
                response.setHeader('ETag', etag)
                return
            }
            response.contentType = 'application/octet-stream'
            response.setHeader('ETag', etag)
            response.setHeader('Cache-Control', 'no-cache')
            response.setHeader('X-Content-Type-Options', 'nosniff')
            response.setHeader('Content-Disposition', 'attachment')
            platform.browse.raw(who, owner, name, sha, request.getParameter('path'), response.outputStream)
        }
    }
}
