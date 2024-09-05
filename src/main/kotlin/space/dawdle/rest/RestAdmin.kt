package space.dawdle.rest

import com.mojang.authlib.GameProfile
import io.javalin.Javalin
import io.javalin.http.BadRequestResponse
import io.javalin.http.UnauthorizedResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.server.WhitelistEntry
import net.minecraft.server.network.ServerPlayerEntity
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
    lateinit var web: Javalin

    override fun onInitializeServer() {
        logger.info("Initializing RestAdmin")
        loadConfig()

        ServerLifecycleEvents.SERVER_STARTING.register(::serverStarting)
        ServerLifecycleEvents.SERVER_STOPPING.register(::serverStopping)
    }

    private fun loadConfig() {
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

    private fun runServer() {
        web =
            Javalin
                .create { config ->
                    config.jetty.defaultHost = this.config.host
                }
                // check if the request has the correct token
                .before { ctx ->
                    if (ctx.header("Authorization") != "Bearer ${config.token}") {
                        throw UnauthorizedResponse("Invalid token")
                    }
                }
                // get the connected players
                .get("/players") { ctx ->
                    ctx.json(connectedPlayers().map { MojangProfile(it.uuid.toString(), it.gameProfile.name) })
                }
                // get the whitelist
                .get("/whitelist") { ctx ->
                    ctx.json(getWhitelist())
                }
                // check if a player is on the whitelist
                .get("/whitelist/{username_or_uuid}") { ctx ->
                    val username = ctx.pathParam("username_or_uuid")
                    val profile = getGameProfile(username)
                    if (profile == null) {
                        throw BadRequestResponse("Failed to find $username")
                    }

                    ctx.json(isWhitelisted(profile))
                }
                // add a player to the whitelist
                .post("/whitelist/{username_or_uuid}") { ctx ->
                    val username = ctx.pathParam("username_or_uuid")
                    val profile = getGameProfile(username)

                    if (profile == null) {
                        throw BadRequestResponse("Failed to find $username")
                    }

                    if (isWhitelisted(profile)) {
                        ctx.status(200).json(MojangProfile(profile.id.toString(), profile.name))
                        return@post
                    }

                    addToWhitelist(profile)
                    ctx.status(200).json(MojangProfile(profile.id.toString(), profile.name))
                }
                // remove a player from the whitelist
                .delete("/whitelist/{username_or_uuid}") { ctx ->
                    val username = ctx.pathParam("username_or_uuid")
                    val profile = getGameProfile(username)
                    if (profile == null) {
                        throw InternalError("Failed to find $username")
                    }

                    removeFromWhitelist(profile)
                    ctx.status(200).json(MojangProfile(profile.id.toString(), profile.name))
                }.start(this.config.port)

        logger.info("Started RestAdmin on ${this.config.host}:${this.config.port}")
    }

    private fun serverStarting(server: MinecraftServer) {
        this.server = server
        runServer()
    }

    private fun serverStopping(_server: MinecraftServer) {
        web.stop()
        logger.info("Stopped RestAdmin")
    }

    private fun getGameProfile(username_or_uuid: String): GameProfile? {
        try {
            val uuid = UUID.fromString(username_or_uuid)
            return server.userCache?.getByUuid(uuid)?.get()
        } catch (e: IllegalArgumentException) {
        }

        return server.userCache?.findByName(username_or_uuid)?.get()
    }

    private fun isWhitelisted(profile: GameProfile): Boolean = server.playerManager.isWhitelisted(profile)

    private fun getWhitelist(): List<String> = server.playerManager.whitelistedNames.toList()

    private fun connectedPlayers(): List<ServerPlayerEntity> = server.playerManager.playerList.filterNotNull()

    private fun addToWhitelist(profile: GameProfile) {
        val username = profile.name
        val entry = WhitelistEntry(profile)
        server.playerManager.whitelist.add(entry)
        logger.info("Added $username to the whitelist")
        server.playerManager.whitelist.save()
    }

    private fun removeFromWhitelist(profile: GameProfile) {
        val username = profile.name
        server.playerManager.whitelist.remove(profile)
        logger.info("Removed $username from the whitelist")
        server.playerManager.whitelist.save()
    }
}
