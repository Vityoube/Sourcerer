package sourcerer

import dev.sourcerer.service.Platform
import grails.boot.GrailsApp
import grails.boot.config.GrailsAutoConfiguration
import groovy.transform.CompileStatic
import org.eclipse.jgit.http.server.GitServlet
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

import java.nio.file.Path

@CompileStatic
class Application extends GrailsAutoConfiguration {
    static void main(String[] args) {
        GrailsApp.run(Application, args)
    }

    @Bean(destroyMethod = 'close')
    Platform platform(Environment env) {
        String secret = env.getProperty('sourcerer.token-secret')
        new Platform(new Platform.Config(
                Path.of(env.getProperty('sourcerer.data-dir', './data')),
                env.getProperty('sourcerer.jdbc-url'),
                env.getProperty('sourcerer.db-user'),
                env.getProperty('sourcerer.db-password'),
                env.getProperty('sourcerer.db-pool-size', Integer, 32),
                secret?.getBytes('UTF-8')))
    }

    /** Git smart-HTTP (clone / fetch / push) served in-process by JGit. */
    @Bean
    ServletRegistrationBean<GitServlet> gitServlet(Platform platform) {
        GitServlet servlet = new GitServlet()
        servlet.setRepositoryResolver(new GitHttp.Resolver(platform))
        servlet.setReceivePackFactory(new GitHttp.ReceivePackFactory(platform))
        ServletRegistrationBean<GitServlet> reg = new ServletRegistrationBean<>(servlet, '/git/*')
        reg.setName('git')
        reg.setLoadOnStartup(1)
        reg
    }

    @Bean
    FilterRegistrationBean<GitHttp.AuthFilter> gitAuthFilter(Platform platform) {
        FilterRegistrationBean<GitHttp.AuthFilter> reg = new FilterRegistrationBean<>(new GitHttp.AuthFilter(platform))
        reg.addUrlPatterns('/git/*')
        reg.setOrder(1)
        reg
    }
}
