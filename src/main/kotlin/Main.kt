package blog

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.features.CallLogging
import io.ktor.features.DefaultHeaders
import io.ktor.html.respondHtml
import io.ktor.locations.*
import io.ktor.request.host
import io.ktor.request.port
import io.ktor.routing.Routing
import io.ktor.routing.param
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.html.*

data class GitHubUser(
    val avatar_url: String,
    val bio: String?,
    val blog: String,
    val company: String?,
    val created_at: String,
    val email: String?,
    val events_url: String,
    val followers: Int,
    val followers_url: String,
    val following: Int,
    val following_url: String,
    val gists_url: String,
    val gravatar_id: String,
    val hireable: String?,
    val html_url: String,
    val id: Int,
    val location: String?,
    val login: String,
    val name: String?,
    val node_id: String,
    val organizations_url: String,
    val public_gists: Int,
    val public_repos: Int,
    val received_events_url: String,
    val repos_url: String,
    val site_admin: Boolean,
    val starred_url: String,
    val subscriptions_url: String,
    val type: String,
    val updated_at: String,
    val url: String
)

@KtorExperimentalLocationsAPI
@Location("/") class index()
@KtorExperimentalLocationsAPI
@Location("/login/{type?}") class login(val type: String = "")

@KtorExperimentalLocationsAPI
fun main(args: Array<String>) {
    embeddedServer(Netty, port = 8080, module = Application::OAuthLoginApplication).start(wait = true)
}

val loginProviders = listOf(
    OAuthServerSettings.OAuth2ServerSettings(
        name = "GitHub",
        authorizeUrl = "https://github.com/login/oauth/authorize",
        accessTokenUrl = "https://github.com/login/oauth/access_token",
        clientId = "a834a021825350c0f152",
        clientSecret = "bb0ae6946db464ebc8760f727d72ff12b47ddd28"
    )
).associateBy { it.name }

@KtorExperimentalLocationsAPI
private fun Application.OAuthLoginApplication() {
    OAuthLoginApplicationWithDeps(
        oauthHttpClient = HttpClient(Apache).apply {
            environment.monitor.subscribe(ApplicationStopping) {
                close()
            }
        }
    )
}

@KtorExperimentalLocationsAPI
fun Application.OAuthLoginApplicationWithDeps(oauthHttpClient: HttpClient) {
    install(DefaultHeaders)
    install(CallLogging)
    install(Locations)
    install(Authentication) {
        oauth("GitHubOAuth") {
            client = oauthHttpClient
            providerLookup = {
                loginProviders[application.locations.resolve<login>(login::class, this).type]
            }
            urlProvider = { p -> redirectUrl(login(p.name), false)}
        }
    }

    install(Routing) {
        get<index> {
            call.loginPage()
        }

        authenticate("GitHubOAuth") {
            location<login>() {
                param("error") {
                    handle {
                        call.loginFailedPage(call.parameters.getAll("error").orEmpty())
                    }
                }

                handle {
                    val principal = call.authentication.principal<OAuthAccessTokenResponse>()
                    val client = HttpClient(Apache) {
                        install(JsonFeature) {
                            serializer = JacksonSerializer()
                        }
                    }

                    val user: GitHubUser? = try {
                        when (principal) {
                            is OAuthAccessTokenResponse.OAuth1a -> client.get<GitHubUser>("https://api.github.com/user") {
                                header("Authorization", "token ${principal.token}")
                            }
                            is OAuthAccessTokenResponse.OAuth2 -> client.get<GitHubUser>("https://api.github.com/user") {
                                header("Authorization", "token ${principal.accessToken}")
                            }
                            else -> null
                        }
                    } catch (e: Exception) {
                        null
                    }

                    if (user != null) {
                        call.loggedInSuccessResponse(user.login)
                    } else {
                        call.loginPage()
                    }
                }
            }
        }
    }
}

@KtorExperimentalLocationsAPI
private fun <T : Any> ApplicationCall.redirectUrl(t: T, secure: Boolean = true): String {
    val hostPort = request.host() + request.port().let { port -> if (port == 80) "" else ":$port" }
    val protocol = when {
        secure -> "https"
        else -> "http"
    }
    return "$protocol://$hostPort${application.locations.href(t)}"
}

@KtorExperimentalLocationsAPI
private suspend fun ApplicationCall.loginPage() {
    respondHtml {
        body {
            for (p in loginProviders) {
                p {
                    button(type = ButtonType.button) {
                        onClick = "window.location.href='${application.locations.href(login(p.key))}'"
                        + "Sign in with GitHub"
                    }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.loginFailedPage(errors: List<String>) {
    respondHtml {
        body {
            h1 {
                +"BAD REQUEST 400"
            }

            for (e in errors) {
                p {
                    +e
                }
            }
        }
    }
}

private suspend fun ApplicationCall.loggedInSuccessResponse(login: String) {
    respondHtml {
        body {
            h1 {
                +"OK 200"
            }
            p {
                +login
            }
        }
    }
}
