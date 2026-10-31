package sourcerer

import dev.sourcerer.core.Principal
import dev.sourcerer.service.Platform
import groovy.transform.CompileStatic

@CompileStatic
class RepoController {
    Platform platform

    def create() {
        Http.run(response) {
            Principal who = Http.requirePrincipal(request, platform)
            Map<String, Object> b = Http.body(request)
            def repo = platform.repos.create(who, Http.str(b, 'name'), Http.str(b, 'description'),
                    Boolean.parseBoolean(String.valueOf(b.get('private'))), Http.str(b, 'defaultBranch'),
                    b.get('initReadme') == null || Boolean.parseBoolean(String.valueOf(b.get('initReadme'))))
            Http.created(response, repo)
        }
    }

    def list() {
        Http.run(response) {
            Principal who = Http.principal(request, platform)
            Http.ok(response, platform.repos.list(who, request.getParameter('owner'),
                    Http.intParam(request, 'limit', 30), Http.intParam(request, 'offset', 0)))
        }
    }

    /** Repository details: metadata + branch/tag counts + latest commit on the default branch. */
    def show() {
        Http.run(response) {
            Http.cached(request, response, platform.browse.details(Http.principal(request, platform),
                    params.org as String, params.repo as String))
        }
    }

    def delete() {
        Http.run(response) {
            platform.repos.delete(Http.requirePrincipal(request, platform), params.org as String, params.repo as String)
            response.status = 204
        }
    }

    def branches() {
        Http.run(response) {
            Http.cached(request, response, platform.browse.branches(Http.principal(request, platform),
                    params.org as String, params.repo as String))
        }
    }

    def commits() {
        Http.run(response) {
            Http.cached(request, response, platform.browse.commits(Http.principal(request, platform),
                    params.org as String, params.repo as String, request.getParameter('ref'),
                    Http.intParam(request, 'page', 1), Http.intParam(request, 'perPage', 30)))
        }
    }
}
