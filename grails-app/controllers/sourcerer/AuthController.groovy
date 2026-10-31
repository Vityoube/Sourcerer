package sourcerer

import dev.sourcerer.core.Principal
import dev.sourcerer.service.AuthService
import dev.sourcerer.service.Platform
import groovy.transform.CompileStatic

@CompileStatic
class AuthController {
    Platform platform

    def register() {
        Http.run(response) {
            Map<String, Object> b = Http.body(request)
            Http.created(response, platform.auth.register(Http.str(b, 'username'), Http.str(b, 'email'), Http.str(b, 'password')))
        }
    }

    def login() {
        Http.run(response) {
            Map<String, Object> b = Http.body(request)
            String token = platform.auth.login(Http.str(b, 'username'), Http.str(b, 'password'))
            Http.ok(response, [token: token, expiresInSeconds: AuthService.LOGIN_TTL_SECONDS])
        }
    }

    /** Long-lived personal access token, usable as the password for git over HTTP. */
    def token() {
        Http.run(response) {
            Principal who = Http.requirePrincipal(request, platform)
            Map<String, Object> b = Http.body(request)
            long days = b.get('days') == null ? 30L : Long.parseLong(b.get('days').toString())
            Http.created(response, [token: platform.auth.issueToken(who, days * 86400L), days: days])
        }
    }

    def me() {
        Http.run(response) {
            Http.ok(response, platform.auth.me(Http.requirePrincipal(request, platform)))
        }
    }
}
