package space.dawdle.rest

import com.mojang.authlib.GameProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.server.WhitelistEntry
import net.minecraft.server.network.ServerPlayerEntity
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.filter.ServerFilters
import org.http4k.format.KotlinxSerialization.auto
import org.http4k.routing.bind
import org.http4k.routing.path
import org.http4k.routing.routes
import org.http4k.server.Http4kServer
import org.http4k.server.Undertow
import org.http4k.server.asServer
import org.slf4j.LoggerFactory
import java.util.UUID

const val DEFAULT_CONFIG: String = """{
  "port": 7070,
  "host": "0.0.0.0",
  "token": "changeme"
}
"""

@Serializable
data class Config(
    val port: Int,
    val host: String,
    val token: String,
)

@Serializable
data class MojangProfile(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
)

object RestAdmin : DedicatedServerModInitializer {
    private val logger = LoggerFactory.getLogger("restadmin")
    lateinit var config: Config
    lateinit var server: MinecraftServer
    lateinit var web: Http4kServer

    override fun onInitializeServer() {
        logger.info("Initializing RestAdmin")
        loadConfig()

        ServerLifecycleEvents.SERVER_STARTING.register(::serverStarting)
        ServerLifecycleEvents.SERVER_STOPPING.register(::serverStopping)
    }

    fun loadConfig() {
        val configPath = FabricLoader.getInstance().configDir.resolve("restadmin.json")

        if (!configPath.toFile().exists()) {
            configPath.toFile().writeText(DEFAULT_CONFIG)
        }

        val configString = configPath.toFile().readText()
        config = Json.decodeFromString<Config>(configString)

        if (config.token == "changeme") {
            logger.error("You must change the token in the config file (config/restadmin.json). Use at least 12 characters. Shutting down.")
            System.exit(1)
        }

        if (config.token.length < 12) {
            logger.error("The token in the config file (config/restadmin.json) is too short. Use at least 12 characters. Shutting down.")
            System.exit(1)
        }
    }

    fun runServer() {
        val playersLense = Body.auto<List<MojangProfile>>().toLens()
        val whitelistLense = Body.auto<List<String>>().toLens()
        val playerLense = Body.auto<MojangProfile>().toLens()
        val booleanLense = Body.auto<Boolean>().toLens()

        web =
            ServerFilters
                .BearerAuth(config.token)
                .then(
                    routes(
                        "/players" bind Method.GET to {
                            val players = connectedPlayers().map { MojangProfile(it.uuid.toString(), it.gameProfile.name) }
                            playersLense(players, Response(Status.OK))
                        },
                        "/whitelist" bind Method.GET to {
                            val whitelist = getWhitelist()
                            whitelistLense(whitelist, Response(Status.OK))
                        },
                        "/whitelist/{username_or_uuid}" bind Method.GET to { req ->
                            val username = req.path("username_or_uuid").orEmpty()
                            getGameProfile(username)?.let { profile ->
                                booleanLense(isWhitelisted(profile), Response(Status.OK))
                            } ?: Response(Status.BAD_REQUEST).body("Failed to find $username")
                        },
                        "/whitelist/{username_or_uuid}" bind Method.POST to { req ->
                            val username = req.path("username_or_uuid").orEmpty()
                            val profile = getGameProfile(username)

                            if (profile == null) {
                                Response(Status.BAD_REQUEST).body("Failed to find $username")
                            } else {
                                logger.info("Adding ${profile.name} (${profile.id}) to the whitelist")
                                addToWhitelist(profile)
                                playerLense(MojangProfile(profile.id.toString(), profile.name), Response(Status.OK))
                            }
                        },
                        "/whitelist/{username_or_uuid}" bind Method.DELETE to { req ->
                            val username = req.path("username_or_uuid").orEmpty()
                            val profile = getGameProfile(username)
                            if (profile == null) {
                                logger.error("Failed to find $username while removing from whitelist, skipping")
                                Response(Status.INTERNAL_SERVER_ERROR).body("Failed to find $username")
                            } else {
                                logger.info("Removing ${profile.name} (${profile.id}) from the whitelist")
                                removeFromWhitelist(profile)
                                playerLense(MojangProfile(profile.id.toString(), profile.name), Response(Status.OK))
                            }
                        },
                    ),
                ).asServer(Undertow(config.port))

        web.start()
        logger.info("Started RestAdmin on ${this.config.host}:${this.config.port}")
    }

    fun serverStarting(server: MinecraftServer) {
        this.server = server
        runServer()
    }

    fun serverStopping(_server: MinecraftServer) {
        web.stop()
        logger.info("Stopped RestAdmin")
    }

    fun getGameProfile(username_or_uuid: String): GameProfile? {
        try {
            val uuid = UUID.fromString(username_or_uuid)
            return server.userCache?.getByUuid(uuid)?.get()
        } catch (e: IllegalArgumentException) {
        }

        return server.userCache?.findByName(username_or_uuid)?.get()
    }

    fun isWhitelisted(profile: GameProfile): Boolean = server.playerManager.isWhitelisted(profile)

    fun getWhitelist(): List<String> = server.playerManager.whitelistedNames.toList()

    fun connectedPlayers(): List<ServerPlayerEntity> = server.playerManager.playerList.filterNotNull()

    fun addToWhitelist(profile: GameProfile) {
        val entry = WhitelistEntry(profile)
        server.playerManager.whitelist.add(entry)
        server.playerManager.whitelist.save()
    }

    fun removeFromWhitelist(profile: GameProfile) {
        server.playerManager.whitelist.remove(profile)
        server.playerManager.whitelist.save()
    }
}
