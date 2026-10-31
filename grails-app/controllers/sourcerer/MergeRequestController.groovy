package sourcerer

import dev.sourcerer.core.Principal
import dev.sourcerer.service.Platform
import groovy.transform.CompileStatic

@CompileStatic
class MergeRequestController {
    Platform platform

    def list() {
        Http.run(response) {
            Http.ok(response, platform.mergeRequests.list(Http.principal(request, platform), params.org as String,
                    params.repo as String, request.getParameter('state'),
                    Http.intParam(request, 'limit', 30), Http.intParam(request, 'offset', 0)))
        }
    }

    def create() {
        Http.run(response) {
            Principal who = Http.requirePrincipal(request, platform)
            Map<String, Object> b = Http.body(request)
            Http.created(response, platform.mergeRequests.create(who, params.org as String, params.repo as String,
                    Http.str(b, 'title'), Http.str(b, 'description'), Http.str(b, 'sourceBranch'), Http.str(b, 'targetBranch')))
        }
    }

    /** {@code ?check=true} additionally dry-runs the merge and reports conflicts. */
    def show() {
        Http.run(response) {
            Principal who = Http.principal(request, platform)
            String owner = params.org as String, name = params.repo as String
            int number = Http.number(params.number as String)
            def mr = platform.mergeRequests.get(who, owner, name, number)
            Map<String, Object> out = [mergeRequest: mr] as Map<String, Object>
            if ('true' == request.getParameter('check') && mr.state() == 'open') {
                out.put('mergeability', platform.mergeRequests.mergeability(who, owner, name, number))
            }
            Http.ok(response, out)
        }
    }

    def diff() {
        Http.run(response) {
            String d = platform.mergeRequests.diff(Http.principal(request, platform), params.org as String,
                    params.repo as String, Http.number(params.number as String))
            Http.ok(response, [diff: d])
        }
    }

    def merge() {
        Http.run(response) {
            Principal who = Http.requirePrincipal(request, platform)
            Http.ok(response, platform.mergeRequests.merge(who, params.org as String, params.repo as String,
                    Http.number(params.number as String)))
        }
    }

    /** PATCH {"state": "open"|"closed"} */
    def update() {
        Http.run(response) {
            Principal who = Http.requirePrincipal(request, platform)
            Http.ok(response, platform.mergeRequests.setState(who, params.org as String, params.repo as String,
                    Http.number(params.number as String), Http.str(Http.body(request), 'state')))
        }
    }
}
