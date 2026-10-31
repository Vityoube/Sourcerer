package sourcerer

class UrlMappings {
    static mappings = {
        // Web UI: a static single-page app (src/main/resources/static), served by Grails under /static/**.
        "/"(uri: '/static/index.html')

        "/api/health"(controller: 'health', action: 'index')

        "/api/auth/register"(controller: 'auth', action: [POST: 'register'])
        "/api/auth/login"(controller: 'auth', action: [POST: 'login'])
        "/api/auth/token"(controller: 'auth', action: [POST: 'token'])
        "/api/user"(controller: 'auth', action: [GET: 'me'])

        "/api/repos"(controller: 'repo') { action = [GET: 'list', POST: 'create'] }
        "/api/repos/$org/$repo"(controller: 'repo') { action = [GET: 'show', DELETE: 'delete'] }
        "/api/repos/$org/$repo/branches"(controller: 'repo', action: [GET: 'branches'])
        "/api/repos/$org/$repo/commits"(controller: 'repo', action: [GET: 'commits'])

        "/api/repos/$org/$repo/tree"(controller: 'browse', action: [GET: 'tree'])
        "/api/repos/$org/$repo/blob"(controller: 'browse', action: [GET: 'blob'])
        "/api/repos/$org/$repo/raw"(controller: 'browse', action: [GET: 'raw'])

        "/api/repos/$org/$repo/issues"(controller: 'issue') { action = [GET: 'list', POST: 'create'] }
        "/api/repos/$org/$repo/issues/$number"(controller: 'issue') { action = [GET: 'show', PATCH: 'update'] }
        "/api/repos/$org/$repo/issues/$number/comments"(controller: 'issue') { action = [GET: 'comments', POST: 'addComment'] }

        "/api/repos/$org/$repo/merge-requests"(controller: 'mergeRequest') { action = [GET: 'list', POST: 'create'] }
        "/api/repos/$org/$repo/merge-requests/$number"(controller: 'mergeRequest') { action = [GET: 'show', PATCH: 'update'] }
        "/api/repos/$org/$repo/merge-requests/$number/diff"(controller: 'mergeRequest', action: [GET: 'diff'])
        "/api/repos/$org/$repo/merge-requests/$number/merge"(controller: 'mergeRequest', action: [POST: 'merge'])

        "404"(controller: 'health', action: 'notFound')
        "500"(controller: 'health', action: 'serverError')
    }
}
