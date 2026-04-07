package net.matty.SimpleDifficultyScaler;

import com.mojang.logging.LogUtils;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(SimpleDifficultyScaler.MOD_ID)
public class SimpleDifficultyScaler
{
    // Define mod id in a common place for everything to reference
    public static final String MOD_ID = "difficultyscaler";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final String WORLD_TIER_DATA_ID = MOD_ID + "_world_tier";

    public enum Tier {
        STARTING(0, "Starting"),
        ECLIPSE(1, "Eclipse"),
        MONSOON(2, "Monsoon"),
        NIGHTMARE(3, "Nightmare"),
        HELL(4, "Hell"),
        INFERNO(5, "Inferno"),
        TORMENT(6, "Torment");

        public final int id;
        public final String displayName;

        Tier(int id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public static Tier fromId(int id) {
            for (Tier tier : values()) {
                if (tier.id == id) return tier;
            }
            return STARTING;
        }
    }

    private static final class WorldTierSavedData extends SavedData {
        private static final String TAG_TIER = "tier";
        private int tier;

        private WorldTierSavedData() {
            this.tier = 0;
        }

        private static WorldTierSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
            WorldTierSavedData data = new WorldTierSavedData();
            data.tier = tag.getInt(TAG_TIER);
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putInt(TAG_TIER, this.tier);
            return tag;
        }
    }

    private static WorldTierSavedData getWorldTierData(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(
                WorldTierSavedData::new,
                WorldTierSavedData::load,
                DataFixTypes.SAVED_DATA_COMMAND_STORAGE
            ),
            WORLD_TIER_DATA_ID
        );
    }

    public SimpleDifficultyScaler()
    {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);



        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ForgeConfigSpec so that Forge can create and load the config file for us
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {

    }

    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event)
    {

    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {

    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("sds")
                .then(Commands.literal("currentTier")
                    .executes(ctx -> {
                        CommandSourceStack source = ctx.getSource();
                        ServerLevel level = source.getLevel();

                        int currentTierId = getCurrentTier(level);
                        Tier tier = Tier.fromId(currentTierId);

                        source.sendSuccess(
                            () -> Component.literal("Current world tier: " + tier.id + " (" + tier.displayName + ")"),
                            false
                        );
                        return 1;
                    })
                )
                .then(Commands.literal("settier")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("tier", IntegerArgumentType.integer(0, 6))
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            ServerLevel level = source.getLevel();

                            int newTierId = IntegerArgumentType.getInteger(ctx, "tier");
                            setCurrentTier(level, newTierId);

                            Tier tier = Tier.fromId(newTierId);
                            source.sendSuccess(
                                () -> Component.literal("World tier set to: " + tier.id + " (" + tier.displayName + ")"),
                                true
                            );
                            return 1;
                        })
                    )
                )
                .then(Commands.literal("tiers")
                    .executes(ctx -> {
                        CommandSourceStack source = ctx.getSource();
                        source.sendSuccess(() -> Component.literal(getTierListText()), false);
                        return 1;
                    })
                )
        );
    }

    private static int getCurrentTier(ServerLevel level) {
        return getWorldTierData(level).tier;
    }

    private static void setCurrentTier(ServerLevel level, int tier) {
        WorldTierSavedData data = getWorldTierData(level);
        data.tier = tier;
        data.setDirty();
    }

    private static String getTierListText() {
        return "Tier list:\n"
            + "0: Starting\n"
            + "1: Eclipse\n"
            + "2: Monsoon\n"
            + "3: Nightmare\n"
            + "4: Hell\n"
            + "5: Inferno\n"
            + "6: Torment";
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {


        }
    }
}
