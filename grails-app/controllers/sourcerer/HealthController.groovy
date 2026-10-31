package sourcerer

import groovy.transform.CompileStatic


@CompileStatic
class HealthController {
    private static final byte[] OK = '{"status":"ok"}'.getBytes('UTF-8')

    def index() {
        Http.send(response, 200, OK, null)
    }

    def notFound() {
        Http.error(response, 404, 'not found')
    }

    def serverError() {
        Http.error(response, 500, 'internal error')
    }
}
