package com.pi.mobber;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.sun.jdi.connect.Connector;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import com.mojang.brigadier.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class Mobber implements ModInitializer {
    public static final String MOD_ID = "mobber";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Lists for our random events
    private static final List<EntityType<?>> SPAWNABLE_MOBS = new ArrayList<>();
    private static final List<Item> GIVEABLE_ITEMS = new ArrayList<>();
    private static final List<String> SPAWNABLE_STRUCTURES = new ArrayList<>();

    private static final Random RANDOM = new Random();
    // Define the new, separate intervals in Ticks (seconds * 20)
    private static int ITEM_INTERVAL_TICKS = 10 * 20;
    private static int MOB_INTERVAL_TICKS = 15 * 20;
    private static int STRUCTURE_INTERVAL_TICKS = 60 * 20;
    private static Boolean SHOW_MOD_MESSAAGES = Boolean.TRUE;

    // Three separate maps to track each event timer for each player
    private static final Map<UUID, Integer> playerItemTimers = new HashMap<>();
    private static final Map<UUID, Integer> playerMobTimers = new HashMap<>();
    private static final Map<UUID, Integer> playerStructureTimers = new HashMap<>();

    @Override
    public void onInitialize() {
        LOGGER.info("Mobber Mod has been initialized!");
        populateMobList();
        populateItemList();
        populateStructureList();

        // Event to add players to all timer maps when they join the server
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            UUID playerUuid = handler.getPlayer().getUuid();
            playerItemTimers.put(playerUuid, ITEM_INTERVAL_TICKS);
            playerMobTimers.put(playerUuid, MOB_INTERVAL_TICKS);
            playerStructureTimers.put(playerUuid, STRUCTURE_INTERVAL_TICKS);
        });

        // Event to remove players from all maps when they leave
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID playerUuid = handler.getPlayer().getUuid();
            playerItemTimers.remove(playerUuid);
            playerMobTimers.remove(playerUuid);
            playerStructureTimers.remove(playerUuid);
        });


        // --- commands to change the time for random events ---  //

        // command to change time for random item
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("randomItemTimeSet")
                    .then(CommandManager.argument("value", IntegerArgumentType.integer())
                            .suggests(new PlayerSuggestionProvider())
                            .executes(Mobber::randomItemTimeSet)));

        });
        // command to change time for random mob
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("randomMobTimeSet")
                    .then(CommandManager.argument("value", IntegerArgumentType.integer())
                            .suggests(new PlayerSuggestionProvider())
                            .executes(Mobber::randomMobTimeSet)));

        });
        // command to change time for random structure
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("randomStructureTimeSet")
                    .then(CommandManager.argument("value", IntegerArgumentType.integer())
                            .suggests(new PlayerSuggestionProvider())
                            .executes(Mobber::randomStructureTimeSet)));

        });



        // command to display mod messages
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("randomEventMessagesInChat")
                    .then(CommandManager.argument("value", BoolArgumentType.bool())
                            .suggests(new MessageInChatSugession())
                            .executes(Mobber::mesegaeInChatDisplayBoolFunction)));

        });



        // The main tick loop that runs 20 times per second on the server
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                UUID playerUuid = player.getUuid();


                // --- Handle Item Timer ---
                int itemTimer = playerItemTimers.getOrDefault(playerUuid, 0);
                if (itemTimer <= 0) {
                    giveRandomItemToPlayer(player);
                    playerItemTimers.put(playerUuid, ITEM_INTERVAL_TICKS);
                } else {
                    playerItemTimers.put(playerUuid, itemTimer - 1);
                }

                // --- Handle Mob Timer ---
                int mobTimer = playerMobTimers.getOrDefault(playerUuid, 0);
                if (mobTimer <= 0) {
                    spawnRandomMobNearPlayer(player);
                    playerMobTimers.put(playerUuid, MOB_INTERVAL_TICKS);
                } else {
                    playerMobTimers.put(playerUuid, mobTimer - 1);
                }

                // --- Handle Structure Timer ---
                int structureTimer = playerStructureTimers.getOrDefault(playerUuid, 0);
                if (structureTimer <= 0) {
                    spawnRandomStructureNearPlayer(player);
                    playerStructureTimers.put(playerUuid, STRUCTURE_INTERVAL_TICKS);
                } else {
                    playerStructureTimers.put(playerUuid, structureTimer - 1);
                }

                // --- Update the on-screen display once per second ---
                // We find the smallest of the three timers to show the "next" event
                int nextEventTimer = Math.min(itemTimer, Math.min(mobTimer, structureTimer));
                if (nextEventTimer % 20 == 0) {
                    int secondsRemaining = nextEventTimer / 20;
                    if (secondsRemaining > 0) {
                        player.sendMessage(Text.literal("Next event in: " + secondsRemaining + "s"), true);
                    }
                }
            }
        });
    }

    private static int randomItemTimeSet(CommandContext<ServerCommandSource> context) {
        int value = IntegerArgumentType.getInteger(context, "value");
        context.getSource().sendFeedback(() -> Text.literal("Countdown of random item set to seconds=".formatted(value)), false);
        ITEM_INTERVAL_TICKS = value*20;
        return 1;
    }

    private static int randomMobTimeSet(CommandContext<ServerCommandSource> context) {
        int value = IntegerArgumentType.getInteger(context, "value");
        context.getSource().sendFeedback(() -> Text.literal("Countdown of random Mob set to seconds=".formatted(value)), false);
        MOB_INTERVAL_TICKS = value*20;
        return 1;
    }
    private static int randomStructureTimeSet(CommandContext<ServerCommandSource> context) {
        int value = IntegerArgumentType.getInteger(context, "value");
        context.getSource().sendFeedback(() -> Text.literal("Countdown of random Structure set to seconds=".formatted(value)), false);
        STRUCTURE_INTERVAL_TICKS = value*20;
        return 1;
    }
    private static int mesegaeInChatDisplayBoolFunction(CommandContext<ServerCommandSource> context) {
        Boolean value = BoolArgumentType.getBool(context, "value");
        context.getSource().sendFeedback(() -> Text.literal("messages of mod will not be displayed in chat".formatted(value)), false);
        SHOW_MOD_MESSAAGES = value;
        return 1;
    }

    // custom suggestion provider
    public class PlayerSuggestionProvider implements SuggestionProvider<ServerCommandSource> {
        @Override
        public CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) throws CommandSyntaxException {
            ServerCommandSource source = context.getSource();
            // ading suggestions
            builder.suggest(1);
            builder.suggest(2);
            builder.suggest(3);
            builder.suggest(10);
            builder.suggest(15);
            builder.suggest(60);
            builder.suggest(120);
            builder.suggest(180);

            // Lock the suggestions after we've modified them.
            return builder.buildFuture();
        }
    }
    public class MessageInChatSugession implements SuggestionProvider<ServerCommandSource> {
        @Override
        public CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) throws CommandSyntaxException {
            ServerCommandSource source = context.getSource();
            // ading suggestions
            builder.suggest("true");
            builder.suggest("false");

            // Lock the suggestions after we've modified them.
            return builder.buildFuture();
        }
    }


    /** Populates the list of mobs that can be spawned. */
    private void populateMobList() {
        List<EntityType<?>> blacklist = List.of(
                EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.ELDER_GUARDIAN,
                EntityType.WARDEN, EntityType.GIANT, EntityType.PLAYER,
                EntityType.ARMOR_STAND, EntityType.ITEM_FRAME
        );
        for (EntityType<?> type : Registries.ENTITY_TYPE) {
            if (type.getSpawnGroup() == SpawnGroup.MONSTER && !blacklist.contains(type)) {
                SPAWNABLE_MOBS.add(type);
            }
        }
        LOGGER.info("Found {} spawnable mobs.", SPAWNABLE_MOBS.size());
    }

    /** Populates the list of items that can be given. */
    private void populateItemList() {
        List<Item> blacklist = List.of(
                Items.AIR, Items.COMMAND_BLOCK, Items.STRUCTURE_BLOCK, Items.JIGSAW,
                Items.BARRIER, Items.LIGHT, Items.DEBUG_STICK, Items.KNOWLEDGE_BOOK,
                Items.COMMAND_BLOCK_MINECART, Items.SPAWNER, Items.PETRIFIED_OAK_SLAB
        );
        for (Item item : Registries.ITEM) {
            if (!blacklist.contains(item)) {
                GIVEABLE_ITEMS.add(item);
            }
        }
        LOGGER.info("Found {} giveable items.", GIVEABLE_ITEMS.size());
    }

    /** Populates the list of structures that can be placed. */
    private void populateStructureList() {
        SPAWNABLE_STRUCTURES.addAll(Arrays.asList(
                "minecraft:village_plains",
                "minecraft:village_desert",
                "minecraft:village_savanna",
                "minecraft:village_snowy",
                "minecraft:village_taiga",
                "minecraft:pillager_outpost",
                "minecraft:desert_pyramid",
                "minecraft:jungle_pyramid",
                "minecraft:igloo",
                "minecraft:swamp_hut",
                "minecraft:mansion"
        ));
        LOGGER.info("Loaded {} spawnable structures.", SPAWNABLE_STRUCTURES.size());
    }

    /** Spawns a random mob from the list near the player. */
    private void spawnRandomMobNearPlayer(ServerPlayerEntity player) {
        if (SPAWNABLE_MOBS.isEmpty()) return;

        ServerWorld world = (ServerWorld) player.getEntityWorld();
        EntityType<?> randomMobType = SPAWNABLE_MOBS.get(RANDOM.nextInt(SPAWNABLE_MOBS.size()));
        BlockPos spawnPos = findSafeSpawnLocation(world, player.getBlockPos(), 1, 10);

        if (spawnPos != null) {
            randomMobType.spawn(world, spawnPos, SpawnReason.EVENT);
        } else {
            player.sendMessage(Text.literal("Could not find a safe place to spawn a mob!"), false);
        }
    }

    /** Gives a random item from the list to the player. */
    private void giveRandomItemToPlayer(ServerPlayerEntity player) {
        if (GIVEABLE_ITEMS.isEmpty()) return;
        Item randomItem = GIVEABLE_ITEMS.get(RANDOM.nextInt(GIVEABLE_ITEMS.size()));
        ItemStack itemStack = new ItemStack(randomItem);
        player.getInventory().insertStack(itemStack);
        player.sendMessage(Text.literal("You received: " + randomItem.getName().getString()), true);
    }

    /** Attempts to place a random structure at the player's location using the /place command. */
    private void spawnRandomStructureNearPlayer(ServerPlayerEntity player) {
        if (SPAWNABLE_STRUCTURES.isEmpty()) return;
        MinecraftServer server = player.getCommandSource().getServer();
        if (server == null) return;

        String structureId = SPAWNABLE_STRUCTURES.get(RANDOM.nextInt(SPAWNABLE_STRUCTURES.size()));
        String command = "place structure " + structureId;

        // The source needs to have operator-level permissions to run /place
        ServerCommandSource source = player.getCommandSource().withSilent();
        if (SHOW_MOD_MESSAAGES) {
            player.sendMessage(Text.literal("Attempting to build a " + structureId.replace("minecraft:", "") + "..."), false);
        }
        LOGGER.info("Executing command for player {}: {}", player.getName().getString(), command);

        // This Fabric API helper method parses and executes the command for us.

        server.getCommandManager().parseAndExecute(source, command);

    }

    /** Tries to find a safe location (solid ground with air above) for mob spawning. */
    private BlockPos findSafeSpawnLocation(World world, BlockPos origin, int radius, int attempts) {
        for (int i = 0; i < attempts; i++) {
            int x = origin.getX() + RANDOM.nextInt(radius * 2 + 1) - radius;
            int z = origin.getZ() + RANDOM.nextInt(radius * 2 + 1) - radius;
            BlockPos.Mutable mutablePos = new BlockPos.Mutable(x, origin.getY() + 5, z);

            while (mutablePos.getY() > world.getBottomY() && world.isAir(mutablePos)) {
                mutablePos.move(0, -1, 0);
            }
            mutablePos.move(0, 1, 0);

            if (world.getBlockState(mutablePos.down()).isSolid() && world.isAir(mutablePos) && world.isAir(mutablePos.up())) {
                return mutablePos.toImmutable();
            }
        }
        return null;
    }
}