package sourcerer

import dev.sourcerer.core.Principal
import dev.sourcerer.service.Platform
import groovy.transform.CompileStatic

@CompileStatic
class IssueController {
    Platform platform

    def list() {
        Http.run(response) {
            Http.ok(response, platform.issues.list(Http.principal(request, platform), params.org as String,
                    params.repo as String, request.getParameter('state'),
                    Http.intParam(request, 'limit', 30), Http.intParam(request, 'offset', 0)))
        }
    }

    def create() {
        Http.run(response) {
            Principal who = Http.requirePrincipal(request, platform)
            Map<String, Object> b = Http.body(request)
            Http.created(response, platform.issues.create(who, params.org as String, params.repo as String,
                    Http.str(b, 'title'), Http.str(b, 'body')))
        }
    }

    def show() {
        Http.run(response) {
            Principal who = Http.principal(request, platform)
            Http.ok(response, platform.issues.get(who, params.org as String, params.repo as String,
                    Http.number(params.number as String)))
        }
    }

    /** PATCH {"state": "open"|"closed"} */
    def update() {
        Http.run(response) {
            Principal who = Http.requirePrincipal(request, platform)
            Http.ok(response, platform.issues.setState(who, params.org as String, params.repo as String,
                    Http.number(params.number as String), Http.str(Http.body(request), 'state')))
        }
    }

    def comments() {
        Http.run(response) {
            Http.ok(response, platform.issues.comments(Http.principal(request, platform), params.org as String,
                    params.repo as String, Http.number(params.number as String)))
        }
    }

    def addComment() {
        Http.run(response) {
            Principal who = Http.requirePrincipal(request, platform)
            Http.created(response, platform.issues.comment(who, params.org as String, params.repo as String,
                    Http.number(params.number as String), Http.str(Http.body(request), 'body')))
        }
    }
}
