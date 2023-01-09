package de.modprog.challenges

import com.fasterxml.jackson.annotation.*
import com.fasterxml.jackson.annotation.JsonSubTypes.Type
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.util.StdConverter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType
import de.modprog.challenges.Main.Companion.challenges
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.StringNbtReader
import net.minecraft.registry.RegistryKeys
import net.minecraft.resource.ResourceManager
import net.minecraft.resource.ResourceType
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory


private val invalidEnumException = DynamicCommandExceptionType { value: Any ->
    Text.translatable("argument.enum.invalid", value)
}

class Main : ModInitializer {
    companion object {
        var challenges: HashMap<String, Challenge> = HashMap()
    }

    private val logger = LoggerFactory.getLogger("challenges")

    override fun onInitialize() {
        ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(ResourceLoader)

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(literal("challenges").then(literal("list").executes { context ->
                context.source.sendMessage(
                    Text.literal(challenges.asIterable()
                        .joinToString("\n") { challenge -> "${challenge.key}: ${challenge.value.name}" })
                )
                1
            }).then(literal("show").then(argument(
                "challenge", StringArgumentType.string()
            ).suggests { _, builder ->
                for (challenge in challenges.keys) {
                    if (challenge.startsWith(builder.remaining)) {
                        builder.suggest(challenge)
                    }
                }; builder.buildFuture()
            }.executes { context ->
                val key = context.getArgument(
                    "challenge", String::class.java
                )
                context.source.sendMessage(
                    Text.literal(
                        challenges.getOrElse(key) { throw invalidEnumException.create(key) }.toString()
                    )
                )
                1
            })
            )
            )
        }
    }
}

object ResourceLoader : SimpleSynchronousResourceReloadListener {
    private val logger = LoggerFactory.getLogger("challenges")
    private val mapper = jacksonObjectMapper()

    override fun getFabricId(): Identifier {
        return Identifier("challenges", "challenges")
    }

    override fun reload(manager: ResourceManager) {
        challenges.clear()
        for (resource in manager.findResources("challenges") { path -> path.path.endsWith(".json") }) {
            try {
                val challenge: Challenge = resource.value.inputStream.use { stream -> mapper.createParser(stream) }
                    .use { parser -> mapper.readValue(parser) }
                // Get the id (only the basename of the file)
                val id = resource.key.path.substringAfterLast("/").substringBefore(".")
                logger.info("Loaded challenge: {}", challenge)
                challenges[id] = challenge
            } catch (e: Exception) {
                logger.error("Exception occurred while parsing challenge ${resource.key}: $e")
            }
        }
    }
}

data class Challenge(
    val name: String, val description: String?, val manual: Boolean = false, val rewards: List<Reward> = listOf()
)

@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes(Type(ItemReward::class))
abstract class Reward {
    abstract fun applyTo(player: PlayerEntity)
}


@JsonIgnoreProperties(ignoreUnknown = true)
data class ItemReward(
    val item: Identifier,
    val amount: Int = 1,
    @JsonDeserialize(converter = StringToNbt::class) val nbt: NbtCompound? = null
) : Reward() {
    override fun applyTo(player: PlayerEntity) {
        val item = player.world.registryManager.get(RegistryKeys.ITEM).get(item)
        val itemStack = ItemStack(item, amount)
        nbt?.let { itemStack.nbt = it }
        player.giveItemStack(itemStack)
    }

    private class StringToNbt : StdConverter<String, NbtCompound>() {
        override fun convert(it: String): NbtCompound = StringNbtReader(
            StringReader(
                if (it.startsWith('{')) {
                    it
                } else {
                    "{$it}"
                }
            )
        ).parseCompound()
    }
}
