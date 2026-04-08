package net.matty.SimpleDifficultyScaler;

import com.mojang.logging.LogUtils;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Bogged;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Stray;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.WitherSkeleton;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.animal.horse.SkeletonHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.EventPriority;
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

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(SimpleDifficultyScaler.MOD_ID)
public class SimpleDifficultyScaler
{
    // Define mod id in a common place for everything to reference
    public static final String MOD_ID = "difficultyscaler";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final String WORLD_TIER_DATA_ID = MOD_ID + "_world_tier";
    /** Set in FinalizeSpawn when the diamond-sword roll succeeds; consumed in EntityJoinLevel (see stomp below). */
    private static final String PENDING_DIAMOND_SWORD_TAG = MOD_ID + ":pending_diamond_sword";
    /** Nightmare (tier 3+): same roll path as Monsoon diamond sword, but upgrades to netherite. */
    private static final String PENDING_NETHERITE_SWORD_TAG = MOD_ID + ":pending_netherite_sword";
    /** Hell+ (tier 4+): roll for a guaranteed Sharpness V netherite sword (applied after vanilla equips). */
    private static final String PENDING_SHARPNESS_NETHERITE_SWORD_TAG = MOD_ID + ":pending_sharpness_netherite_sword";
    /** Tier 4+: roll for a Flame bow on skeletons (applied after vanilla equips). */
    private static final String PENDING_SKELETON_FLAME_BOW_TAG = MOD_ID + ":pending_skeleton_flame_bow";
    /** Tier 4+: roll for a Power V bow on skeletons (applied after vanilla equips). */
    private static final String PENDING_SKELETON_POWER5_BOW_TAG = MOD_ID + ":pending_skeleton_power5_bow";
    /** Hell Skeleton Knight mount: vanilla skeleton horses do not run hostile despawn logic; we mirror {@link MobCategory#MONSTER} rules on tick. */
    private static final String SKELETON_KNIGHT_HORSE_TAG = MOD_ID + ":skeleton_knight_mount";

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

    private record TierTuning(
        float pillagerUpgradeChance,
        /** Tier 3+ (Nightmare and up): natural monsters can upgrade to a Vindicator; lower tiers use 0. */
        float vindicatorUpgradeChance,
        /** Tier 3+ (Nightmare and up): Piglins in the Nether can upgrade to Piglin Brutes outside bastions; lower tiers use 0. */
        float piglinBruteUpgradeChance,
        /** Tier 3+ (Nightmare and up): chance to spawn an additional Stray/Husk/Bogged near a natural Overworld spawn. */
        float overworldVariantExtraSpawnChance,
        /** Tier 5+ (Inferno and up): chance to spawn an additional Nether mob near a natural Overworld hostile spawn (addition, not replacement). */
        float overworldNetherMobExtraSpawnChance,
        float witchUpgradeChance,
        float extraArmorChance,
        float extraWeaponChance,
        float armorPieceChance,
        float extraEnchantChance,
        int enchantLevelMin,
        int enchantLevelMaxInclusive,
        float potionVisibleChance,
        float potionHiddenChance,
        /** Tier 2 (Monsoon) only in practice: Zombies + Wither Skeletons roll to spawn with a diamond sword. Other tiers: 0. */
        float monsoonDiamondSwordChance,
        /** Tier 3+ (Nightmare and up): additional independent roll to spawn with a netherite sword. */
        float nightmareNetheriteSwordChance,
        /** Tier 4 (Hell): chance a natural Skeleton spawn becomes a mounted, armored Skeleton Knight. Other tiers: 0. */
        float skeletonKnightReplaceChance,
        /** Chance a natural {@link EntityType#SPIDER} spawn gains a Skeleton rider (spider jockey). Overworld-oriented; 0 disables. */
        float spiderJockeyChance,
        /** Overworld: chance a natural hostile spawn is replaced by a {@link EntityType#RAVAGER} (same candidate pool as Witch / Pillager / Vindicator). 0 disables. */
        float ravagerUpgradeChance,
        /** Overworld: chance a natural hostile spawn is replaced by a {@link EntityType#BREEZE}. Same candidate pool as other Overworld replacements. 0 disables. */
        float breezeReplaceChance,
        /** Tier 4+ (Hell and up): Zombies + Wither Skeletons roll to spawn with a Sharpness V netherite sword. */
        float hellSharpnessNetheriteSwordChance,
        /** Tier 4+: chance a Skeleton-type mob spawns with a Flame bow. */
        float skeletonFlameBowChance,
        /** Tier 4+: chance a Skeleton-type mob spawns with a Power V bow. */
        float skeletonPower5BowChance,
        /** Tier 4 (Hell): chance a Vindicator spawns as "Johnny" with a Sharpness V axe. */
        float hellJohnnyVindicatorChance,
        /** Tier 5+ (Inferno and up): independent hidden/no-particle roll per spawn. */
        float infernoOozingChance,
        /** Tier 5+ (Inferno and up): independent hidden/no-particle roll per spawn. */
        float infernoInfestationChance,
        /** Tier 5+ (Inferno and up): independent hidden/no-particle roll per spawn. */
        float infernoWindChargingChance,
        /** Tier 5+ (Inferno and up): chance a natural Creeper spawn is upgraded to a charged Creeper. */
        float infernoChargedCreeperChance,
        /** Tier 6 (Torment): chance any spawning {@link EntityType#RAVAGER} gains an {@link EntityType#EVOKER} rider. */
        float tormentRavagerEvokerRiderChance
    ) {
    }

    private static TierTuning getTuning(ServerLevel level) {
        int tierId = getCurrentTier(level);
        return switch (tierId) {
            case 1 -> new TierTuning(
                0.0f,  // pillagerUpgradeChance
                0.0f,  // vindicatorUpgradeChance
                0.0f,  // piglinBruteUpgradeChance
                0.0f,  // overworldVariantExtraSpawnChance
                0.0f,  // overworldNetherMobExtraSpawnChance
                0.008f, // witchUpgradeChance
                0.25f, // extraArmorChance
                0.25f, // extraWeaponChance
                0.35f, // armorPieceChance
                0.25f, // extraEnchantChance
                5,     // enchantLevelMin
                15,    // enchantLevelMaxInclusive
                0.0f,  // potionVisibleChance
                0.0f,  // potionHiddenChance
                0.0f,  // monsoonDiamondSwordChance
                0.0f,  // nightmareNetheriteSwordChance
                0.0f,  // skeletonKnightReplaceChance
                0.0f,  // spiderJockeyChance
                0.0f,  // ravagerUpgradeChance
                0.0f,  // breezeReplaceChance
                0.0f,  // hellSharpnessNetheriteSwordChance
                0.0f,  // skeletonFlameBowChance
                0.0f,  // skeletonPower5BowChance
                0.0f,  // hellJohnnyVindicatorChance
                0.0f,  // infernoOozingChance
                0.0f,  // infernoInfestationChance
                0.0f,  // infernoWindChargingChance
                0.0f,  // infernoChargedCreeperChance
                0.0f   // tormentRavagerEvokerRiderChance
            );
            case 2 -> new TierTuning(
                0.05f, // pillagerUpgradeChance
                0.0f,  // vindicatorUpgradeChance
                0.0f,  // piglinBruteUpgradeChance
                0.0f,  // overworldVariantExtraSpawnChance
                0.0f,  // overworldNetherMobExtraSpawnChance
                0.02f, // witchUpgradeChance
                0.30f, // extraArmorChance
                0.30f, // extraWeaponChance
                0.40f, // armorPieceChance
                0.30f, // extraEnchantChance
                10,    // enchantLevelMin (enchanting power)
                20,    // enchantLevelMaxInclusive
                0.09f, // potionVisibleChance: independent roll per effect (Speed, Strength, Health Boost, Jump Boost); amp 0–1; particles
                0.10f, // potionHiddenChance: independent roll per effect (Fire Resist, Resistance, Water Breathing); amp 0; minimal HUD
                0.05f, // monsoonDiamondSwordChance: Zombie mobs + Wither Skeleton → diamond sword (Monsoon special)
                0.0f,  // nightmareNetheriteSwordChance
                0.0f,  // skeletonKnightReplaceChance
                0.01f, // spiderJockeyChance (~vanilla-rare baseline; tune per tier as needed)
                0.0015f, // ravagerUpgradeChance
                0.0f,  // breezeReplaceChance
                0.0f,   // hellSharpnessNetheriteSwordChance
                0.0f,   // skeletonFlameBowChance
                0.0f,   // skeletonPower5BowChance
                0.0f,   // hellJohnnyVindicatorChance
                0.0f,   // infernoOozingChance
                0.0f,   // infernoInfestationChance
                0.0f,   // infernoWindChargingChance
                0.0f,   // infernoChargedCreeperChance
                0.0f    // tormentRavagerEvokerRiderChance
            );
            case 3 -> new TierTuning(
                0.08f, // pillagerUpgradeChance (up from Monsoon 5%)
                0.04f, // vindicatorUpgradeChance (Nightmare)
                0.14f, // piglinBruteUpgradeChance (Nightmare; Nether-wide piglin->brute)
                0.20f, // overworldVariantExtraSpawnChance (Nightmare; extra Stray/Husk/Bogged spawns)
                0.0f,  // overworldNetherMobExtraSpawnChance
                0.03f, // witchUpgradeChance (same as Monsoon)
                0.35f, // extraArmorChance
                0.35f, // extraWeaponChance
                0.45f, // armorPieceChance
                0.35f, // extraEnchantChance
                20,    // enchantLevelMin
                25,    // enchantLevelMaxInclusive
                0.11f, // potionVisibleChance
                0.13f, // potionHiddenChance    
                0.09f, // monsoonDiamondSwordChance (same behavior at tier 3+)
                0.05f, // nightmareNetheriteSwordChance (additional)
                0.0f,  // skeletonKnightReplaceChance
                0.015f, // spiderJockeyChance
                0.002f, // ravagerUpgradeChance
                0.0f, // breezeReplaceChance
                0.0f, // hellSharpnessNetheriteSwordChance
                0.0f, // skeletonFlameBowChance
                0.0f, // skeletonPower5BowChance
                0.0f, // hellJohnnyVindicatorChance
                0.0f, // infernoOozingChance
                0.0f, // infernoInfestationChance
                0.0f, // infernoWindChargingChance
                0.0f, // infernoChargedCreeperChance
                0.0f  // tormentRavagerEvokerRiderChance
            );
            case 4 -> new TierTuning(
                0.09f, // pillagerUpgradeChance
                0.07f, // vindicatorUpgradeChance
                0.16f, // piglinBruteUpgradeChance
                0.22f, // overworldVariantExtraSpawnChance
                0.0f,  // overworldNetherMobExtraSpawnChance
                0.04f, // witchUpgradeChance
                0.45f, // extraArmorChance
                0.45f, // extraWeaponChance
                0.55f, // armorPieceChance
                0.45f, // extraEnchantChance
                25, // enchantLevelMin
                30, // enchantLevelMaxInclusive
                0.20f, // potionVisibleChance
                0.15f, // potionHiddenChance
                0.15f, // monsoonDiamondSwordChance
                0.10f, // nightmareNetheriteSwordChance
                0.006f, // skeletonKnightReplaceChance (Hell only; other fields match pre-change default for tier 4)
                0.04f, // spiderJockeyChance
                0.03f, // ravagerUpgradeChance
                0.02f, // breezeReplaceChance
                0.02f, // hellSharpnessNetheriteSwordChance
                0.5f, // skeletonFlameBowChance
                0.03f,  // skeletonPower5BowChance
                0.08f,  // hellJohnnyVindicatorChance
                0.0f,   // infernoOozingChance
                0.0f,   // infernoInfestationChance
                0.0f,   // infernoWindChargingChance
                0.0f,   // infernoChargedCreeperChance
                0.0f    // tormentRavagerEvokerRiderChance
            );
            case 5 -> new TierTuning(
                0.10f, // pillagerUpgradeChance
                0.08f, // vindicatorUpgradeChance
                0.18f, // piglinBruteUpgradeChance
                0.25f, // overworldVariantExtraSpawnChance
                0.10f, // overworldNetherMobExtraSpawnChance
                0.05f, // witchUpgradeChance
                0.50f, // extraArmorChance
                0.50f, // extraWeaponChance
                0.60f, // armorPieceChance
                0.50f, // extraEnchantChance
                25,    // enchantLevelMin
                30,    // enchantLevelMaxInclusive
                0.24f, // potionVisibleChance
                0.18f, // potionHiddenChance
                0.18f, // monsoonDiamondSwordChance
                0.12f, // nightmareNetheriteSwordChance
                0.018f, // skeletonKnightReplaceChance
                0.06f, // spiderJockeyChance
                0.04f, // ravagerUpgradeChance
                0.04f, // breezeReplaceChance
                0.03f, // hellSharpnessNetheriteSwordChance
                0.6f,  // skeletonFlameBowChance
                0.03f, // skeletonPower5BowChance
                0.10f, // hellJohnnyVindicatorChance
                0.11f, // infernoOozingChance
                0.11f, // infernoInfestationChance
                0.15f, // infernoWindChargingChance
                0.05f, // infernoChargedCreeperChance
                0.0f   // tormentRavagerEvokerRiderChance
            );
            case 6 -> new TierTuning(
                0.12f, // pillagerUpgradeChance
                0.10f, // vindicatorUpgradeChance
                0.20f, // piglinBruteUpgradeChance
                0.28f, // overworldVariantExtraSpawnChance
                0.12f, // overworldNetherMobExtraSpawnChance
                0.06f, // witchUpgradeChance
                0.55f, // extraArmorChance
                0.55f, // extraWeaponChance
                0.65f, // armorPieceChance
                0.85f, // extraEnchantChance
                30,    // enchantLevelMin
                30,    // enchantLevelMaxInclusive
                0.26f, // potionVisibleChance
                0.20f, // potionHiddenChance
                0.20f, // monsoonDiamondSwordChance
                0.14f, // nightmareNetheriteSwordChance
                0.08f, // skeletonKnightReplaceChance
                0.06f, // spiderJockeyChance
                0.05f, // ravagerUpgradeChance
                0.04f, // breezeReplaceChance
                0.04f, // hellSharpnessNetheriteSwordChance
                0.95f, // skeletonFlameBowChance
                0.33f, // skeletonPower5BowChance
                0.99f, // hellJohnnyVindicatorChance
                0.20f, // infernoOozingChance
                0.20f, // infernoInfestationChance
                0.92f, // infernoWindChargingChance
                0.16f, // infernoChargedCreeperChance
                0.33f  // tormentRavagerEvokerRiderChance
            );
            default -> new TierTuning(
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0,
                0,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,  // skeletonKnightReplaceChance
                0.0f,  // spiderJockeyChance
                0.0f,  // ravagerUpgradeChance
                0.0f,  // breezeReplaceChance
                0.0f,  // hellSharpnessNetheriteSwordChance
                0.0f,  // skeletonFlameBowChance
                0.0f,  // skeletonPower5BowChance
                0.0f,  // hellJohnnyVindicatorChance
                0.0f,  // infernoOozingChance
                0.0f,  // infernoInfestationChance
                0.0f,  // infernoWindChargingChance
                0.0f,  // infernoChargedCreeperChance
                0.0f   // tormentRavagerEvokerRiderChance
            );
        };
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

    private static boolean isTierAtLeast(ServerLevel level, int tier) {
        return getCurrentTier(level) >= tier;
    }

    /**
     * Whether natural-spawn replacement may produce Witches, Pillagers, Vindicators, Ravagers, or Breezes in this dimension.
     * Today: Overworld only. Later tiers can widen this (e.g. Nether / End) by branching on {@code worldTier}
     * or reading tier tuning — keep all dimension policy here so it does not scatter around the spawn handlers.
     */
    private static boolean allowsWitchPillagerReplacementInDimension(ServerLevel level, int worldTier) {
        return level.dimension() == Level.OVERWORLD;
    }

    /** Whether natural-spawn replacement may upgrade Piglins to Piglin Brutes in this dimension. Today: Nether only. */
    private static boolean allowsPiglinBruteReplacementInDimension(ServerLevel level, int worldTier) {
        return level.dimension() == Level.NETHER;
    }

    @SubscribeEvent
    public void onMobFinalizeSpawn(MobSpawnEvent.FinalizeSpawn event) {
        if (!(event.getLevel() instanceof ServerLevelAccessor serverLevelAccessor)) return;
        if (!(serverLevelAccessor.getLevel() instanceof ServerLevel level)) return;
        if (!isTierAtLeast(level, 1)) return; // Tier 1+: Eclipse and above

        Mob mob = event.getEntity();
        RandomSource random = level.getRandom();
        TierTuning tuning = getTuning(level);
        int tier = getCurrentTier(level);

        // Hell (tier 4): rare Skeleton Knight spawn (probability in TierTuning.skeletonKnightReplaceChance).
        // Replaces a normal NATURAL Skeleton spawn with a mounted, fully-armored skeleton (like lightning-trap skeletons).
        if (tuning.skeletonKnightReplaceChance() > 0.0f
            && event.getSpawnType() == MobSpawnType.NATURAL
            && mob.getType() == EntityType.SKELETON
            && mob instanceof Skeleton skeleton
            && random.nextFloat() < tuning.skeletonKnightReplaceChance()) {

            if (tryConvertToSkeletonKnight(level, serverLevelAccessor, event, skeleton, random)) {
                applyMonsoonPotionEffects(skeleton, random, tuning, level);
                return;
            }
        }

        // Spider jockey: natural Spider may roll a Skeleton rider (chance in TierTuning.spiderJockeyChance).
        if (tuning.spiderJockeyChance() > 0.0f
            && event.getSpawnType() == MobSpawnType.NATURAL
            && level.dimension() == Level.OVERWORLD
            && mob.getType() == EntityType.SPIDER
            && mob instanceof Spider spider
            && spider.getPassengers().isEmpty()
            && random.nextFloat() < tuning.spiderJockeyChance()) {

            if (tryAddSpiderJockeyRider(level, serverLevelAccessor, event, spider, random, tuning, tier)) {
                if (tier >= 2) {
                    applyMonsoonPotionEffects(spider, random, tuning, level);
                }
                return;
            }
        }

        // Hell (tier 4): natural Slimes can use size 1–5 (vanilla tops out at 3).
        // Inferno+ (tier 5+): bump max size to 6.
        if (tier >= 4
            && event.getSpawnType() == MobSpawnType.NATURAL
            && mob instanceof Slime slime) {
            int maxSize = (tier >= 5) ? 6 : 5;
            int size = 1 + random.nextInt(maxSize);
            slime.setSize(size, true);
        }

        // Inferno+: chance for natural Creepers to spawn charged.
        if (tier >= 5
            && tuning.infernoChargedCreeperChance() > 0.0f
            && event.getSpawnType() == MobSpawnType.NATURAL
            && mob instanceof Creeper creeper
            && !creeper.isPowered()
            && random.nextFloat() < tuning.infernoChargedCreeperChance()) {
            LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
            if (bolt != null) {
                bolt.moveTo(creeper.getX(), creeper.getY(), creeper.getZ());
                creeper.thunderHit(level, bolt);
            }
        }

        // Nightmare+: Overworld mob density bump — occasionally spawn an additional variant mob (Stray/Husk/Bogged)
        // near a normal natural hostile spawn, without replacing the original spawn.
        if (tier >= 3
            && tuning.overworldVariantExtraSpawnChance() > 0.0f
            && level.dimension() == Level.OVERWORLD
            && event.getSpawnType() == MobSpawnType.NATURAL
            && mob instanceof Monster
            && random.nextFloat() < tuning.overworldVariantExtraSpawnChance()) {
            maybeSpawnNightmareOverworldVariant(level, serverLevelAccessor, event, random, tuning, mob);
        }

        // Inferno+: occasionally spawn a Nether mob near a natural Overworld hostile spawn (addition, not replacement).
        if (tier >= 5
            && tuning.overworldNetherMobExtraSpawnChance() > 0.0f
            && level.dimension() == Level.OVERWORLD
            && event.getSpawnType() == MobSpawnType.NATURAL
            && mob instanceof Monster
            && random.nextFloat() < tuning.overworldNetherMobExtraSpawnChance()) {
            maybeSpawnInfernoOverworldNetherMob(level, serverLevelAccessor, event, random, tuning, mob);
        }

        // Nightmare+: throughout the Nether, occasionally upgrade a natural Piglin spawn to a Piglin Brute
        // (so Brutes aren't effectively "bastion-only" at high tier).
        if (tier >= 3
            && tuning.piglinBruteUpgradeChance() > 0.0f
            && allowsPiglinBruteReplacementInDimension(level, tier)
            && event.getSpawnType() == MobSpawnType.NATURAL
            && mob instanceof Piglin
            && !(mob instanceof PiglinBrute)
            && random.nextFloat() < tuning.piglinBruteUpgradeChance()) {

            PiglinBrute brute = EntityType.PIGLIN_BRUTE.create(level);
            if (brute != null) {
                brute.moveTo(mob.getX(), mob.getY(), mob.getZ(), mob.getYRot(), mob.getXRot());
                brute.finalizeSpawn(serverLevelAccessor, event.getDifficulty(), event.getSpawnType(), null);
                mob.discard();
                level.addFreshEntity(brute);
                applyMonsoonPotionEffects(brute, random, tuning, level);
                return;
            }
        }

        // Monsoon+: occasionally upgrade a natural monster spawn to a Pillager (dimension policy: see allowsWitchPillagerReplacementInDimension).
        if (tier >= 2
            && allowsWitchPillagerReplacementInDimension(level, tier)
            && mob instanceof Monster
            && !(mob instanceof Witch)
            && !(mob instanceof AbstractIllager)
            && event.getSpawnType() == MobSpawnType.NATURAL
            && random.nextFloat() < tuning.pillagerUpgradeChance()) {

            Pillager pillager = EntityType.PILLAGER.create(level);
            if (pillager != null) {
                pillager.moveTo(mob.getX(), mob.getY(), mob.getZ(), mob.getYRot(), mob.getXRot());
                pillager.finalizeSpawn(serverLevelAccessor, event.getDifficulty(), event.getSpawnType(), null);
                mob.discard();
                level.addFreshEntity(pillager);
                applyMonsoonPotionEffects(pillager, random, tuning, level);
                return;
            }
        }

        // Nightmare+: occasionally upgrade a natural monster spawn to a Vindicator (same dimension policy as Pillagers).
        if (tier >= 3
            && tuning.vindicatorUpgradeChance() > 0.0f
            && allowsWitchPillagerReplacementInDimension(level, tier)
            && mob instanceof Monster
            && !(mob instanceof Witch)
            && !(mob instanceof AbstractIllager)
            && event.getSpawnType() == MobSpawnType.NATURAL
            && random.nextFloat() < tuning.vindicatorUpgradeChance()) {

            Vindicator vindicator = EntityType.VINDICATOR.create(level);
            if (vindicator != null) {
                vindicator.moveTo(mob.getX(), mob.getY(), mob.getZ(), mob.getYRot(), mob.getXRot());
                vindicator.finalizeSpawn(serverLevelAccessor, event.getDifficulty(), event.getSpawnType(), null);
                maybeMakeHellJohnnyVindicator(vindicator, random, tuning, tier, level);
                mob.discard();
                level.addFreshEntity(vindicator);
                applyMonsoonPotionEffects(vindicator, random, tuning, level);
                return;
            }
        }

        // Monsoon+: occasionally upgrade a natural monster spawn to a Ravager (same dimension policy and candidate pool as Pillagers / Vindicators / Witches).
        if (tier >= 2
            && tuning.ravagerUpgradeChance() > 0.0f
            && allowsWitchPillagerReplacementInDimension(level, tier)
            && mob instanceof Monster
            && !(mob instanceof Witch)
            && !(mob instanceof AbstractIllager)
            && !(mob instanceof Ravager)
            && event.getSpawnType() == MobSpawnType.NATURAL
            && random.nextFloat() < tuning.ravagerUpgradeChance()) {

            Ravager ravager = EntityType.RAVAGER.create(level);
            if (ravager != null) {
                ravager.moveTo(mob.getX(), mob.getY(), mob.getZ(), mob.getYRot(), mob.getXRot());
                ravager.finalizeSpawn(serverLevelAccessor, event.getDifficulty(), event.getSpawnType(), null);
                mob.discard();
                level.addFreshEntity(ravager);
                applyMonsoonPotionEffects(ravager, random, tuning, level);
                return;
            }
        }

        // Monsoon+: occasionally upgrade a natural monster spawn to a Breeze (trial-chamber mob in vanilla; here, natural Overworld pool).
        if (tier >= 2
            && tuning.breezeReplaceChance() > 0.0f
            && allowsWitchPillagerReplacementInDimension(level, tier)
            && mob instanceof Monster
            && !(mob instanceof Witch)
            && !(mob instanceof AbstractIllager)
            && !(mob instanceof Ravager)
            && !(mob instanceof Breeze)
            && event.getSpawnType() == MobSpawnType.NATURAL
            && random.nextFloat() < tuning.breezeReplaceChance()) {

            Breeze breeze = EntityType.BREEZE.create(level);
            if (breeze != null) {
                breeze.moveTo(mob.getX(), mob.getY(), mob.getZ(), mob.getYRot(), mob.getXRot());
                breeze.finalizeSpawn(serverLevelAccessor, event.getDifficulty(), event.getSpawnType(), null);
                mob.discard();
                level.addFreshEntity(breeze);
                applyMonsoonPotionEffects(breeze, random, tuning, level);
                return;
            }
        }

        // Eclipse+: occasionally upgrade a natural monster spawn to a Witch (dimension policy: see allowsWitchPillagerReplacementInDimension).
        if (allowsWitchPillagerReplacementInDimension(level, tier)
            && mob instanceof Monster
            && !(mob instanceof Witch)
            && !(mob instanceof AbstractIllager)
            && event.getSpawnType() == MobSpawnType.NATURAL
            && random.nextFloat() < tuning.witchUpgradeChance()) {

            Witch witch = EntityType.WITCH.create(level);
            if (witch != null) {
                witch.moveTo(mob.getX(), mob.getY(), mob.getZ(), mob.getYRot(), mob.getXRot());
                witch.finalizeSpawn(serverLevelAccessor, event.getDifficulty(), event.getSpawnType(), null);
                mob.discard();
                level.addFreshEntity(witch);
                if (tier >= 2) {
                    applyMonsoonPotionEffects(witch, random, tuning, level);
                }
                return;
            }
        }

        // Hell (tier 4): chance for Vindicators (natural or otherwise) to become "Johnny" with a Sharpness V axe.
        if (mob instanceof Vindicator vindicator) {
            maybeMakeHellJohnnyVindicator(vindicator, random, tuning, tier, level);
        }

        applyTierGearAndEffects(mob, random, tuning, tier, level);
    }

    private static void maybeMakeHellJohnnyVindicator(Vindicator vindicator, RandomSource random, TierTuning tuning, int tier, ServerLevel level) {
        if (tier != 4) return;
        if (tuning.hellJohnnyVindicatorChance() <= 0.0f) return;
        if (random.nextFloat() >= tuning.hellJohnnyVindicatorChance()) return;
        if (!trySetVindicatorJohnnyFlag(vindicator, true)) {
            // Fallback: vanilla also recognizes the "Johnny" name; use it only if the internal flag can't be set.
            if (vindicator.hasCustomName()) return;
            vindicator.setCustomName(Component.literal("Johnny"));
            vindicator.setCustomNameVisible(false);
        }

        ItemStack axe = new ItemStack(Items.IRON_AXE);
        var enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        axe.enchant(enchantments.getOrThrow(Enchantments.SHARPNESS), 5);
        vindicator.setItemSlot(EquipmentSlot.MAINHAND, axe);
    }

    /**
     * In vanilla 1.21, Vindicator has a package-private boolean {@code isJohnny} that enables the Johnny targeting behavior.
     * We set it reflectively to avoid relying on the visible custom name.
     */
    private static boolean trySetVindicatorJohnnyFlag(Vindicator vindicator, boolean value) {
        try {
            var f = Vindicator.class.getDeclaredField("isJohnny");
            f.setAccessible(true);
            f.setBoolean(vindicator, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean tryAddSpiderJockeyRider(
        ServerLevel level,
        ServerLevelAccessor serverLevelAccessor,
        MobSpawnEvent.FinalizeSpawn event,
        Spider spider,
        RandomSource random,
        TierTuning tuning,
        int tier
    ) {
        Skeleton skeleton = EntityType.SKELETON.create(level);
        if (skeleton == null) return false;

        skeleton.moveTo(spider.getX(), spider.getY(), spider.getZ(), spider.getYRot(), spider.getXRot());
        skeleton.finalizeSpawn(serverLevelAccessor, event.getDifficulty(), MobSpawnType.EVENT, null);
        level.addFreshEntity(skeleton);
        skeleton.startRiding(spider, true);

        applyTierGearAndEffects(skeleton, random, tuning, tier, level);
        return true;
    }

    private static boolean tryConvertToSkeletonKnight(
        ServerLevel level,
        ServerLevelAccessor serverLevelAccessor,
        MobSpawnEvent.FinalizeSpawn event,
        Skeleton skeleton,
        RandomSource random
    ) {
        SkeletonHorse horse = EntityType.SKELETON_HORSE.create(level);
        if (horse == null) return false;

        horse.moveTo(skeleton.getX(), skeleton.getY(), skeleton.getZ(), skeleton.getYRot(), skeleton.getXRot());
        horse.finalizeSpawn(serverLevelAccessor, event.getDifficulty(), MobSpawnType.EVENT, null);
        horse.getPersistentData().putBoolean(SKELETON_KNIGHT_HORSE_TAG, true);
        level.addFreshEntity(horse);

        equipSkeletonKnightArmor(skeleton, random);
        skeleton.startRiding(horse, true);
        return true;
    }

    private static void equipSkeletonKnightArmor(Skeleton skeleton, RandomSource random) {
        // "At least iron armor": keep the baseline iron, with a small upgrade chance to diamond per piece.
        Item helm = random.nextFloat() < 0.10f ? Items.DIAMOND_HELMET : Items.IRON_HELMET;
        Item chest = random.nextFloat() < 0.10f ? Items.DIAMOND_CHESTPLATE : Items.IRON_CHESTPLATE;
        Item legs = random.nextFloat() < 0.10f ? Items.DIAMOND_LEGGINGS : Items.IRON_LEGGINGS;
        Item boots = random.nextFloat() < 0.10f ? Items.DIAMOND_BOOTS : Items.IRON_BOOTS;

        skeleton.setItemSlot(EquipmentSlot.HEAD, new ItemStack(helm));
        skeleton.setItemSlot(EquipmentSlot.CHEST, new ItemStack(chest));
        skeleton.setItemSlot(EquipmentSlot.LEGS, new ItemStack(legs));
        skeleton.setItemSlot(EquipmentSlot.FEET, new ItemStack(boots));
    }

    private static void applyTierGearAndEffects(Mob mob, RandomSource random, TierTuning tuning, int tier, ServerLevel level) {
        if (!isVanillaGearRollingMob(mob)) {
            if (tier >= 2) {
                applyMonsoonPotionEffects(mob, random, tuning, level);
            }
            return;
        }

        // Monsoon+ diamond sword (tuning: monsoonDiamondSwordChance):
        // - Today: Zombie line + all Wither Skeletons at tier >= 2 when the roll passes.
        // - Design: Nether-fortress-focused behavior vs later "Wither Skeletons spawn outside fortresses" should stay
        //   separate — use a different predicate and/or tier gate and tuning field there, instead of overloading this
        //   block (e.g. fortress-only WS: add isNetherFortressSpawn(...) && WitherSkeleton here; open-world WS: new path).
        // Tag is consumed in EntityJoinLevel + multi-tick stomp (vanilla can equip stone sword very late).
        if (tier >= 2
            && tuning.monsoonDiamondSwordChance() > 0.0f
            && random.nextFloat() < tuning.monsoonDiamondSwordChance()
            && (mob instanceof Zombie || mob instanceof WitherSkeleton)) {
            mob.getPersistentData().putBoolean(PENDING_DIAMOND_SWORD_TAG, true);
        }

        // Nightmare+ additional roll for netherite sword (does not replace the diamond roll).
        if (tier >= 3
            && tuning.nightmareNetheriteSwordChance() > 0.0f
            && random.nextFloat() < tuning.nightmareNetheriteSwordChance()
            && (mob instanceof Zombie || mob instanceof WitherSkeleton)) {
            mob.getPersistentData().putBoolean(PENDING_NETHERITE_SWORD_TAG, true);
        }

        // Hell+ additional roll for a Sharpness V netherite sword (stronger than the tier-3 netherite roll).
        // Applied later in EntityJoinLevel + multi-tick stomp (vanilla can equip late).
        if (tier >= 4
            && tuning.hellSharpnessNetheriteSwordChance() > 0.0f
            && random.nextFloat() < tuning.hellSharpnessNetheriteSwordChance()
            && (mob instanceof Zombie || mob instanceof WitherSkeleton)) {
            mob.getPersistentData().putBoolean(PENDING_SHARPNESS_NETHERITE_SWORD_TAG, true);
        }

        // Tier 4+ skeleton bow specials (applied later via EntityJoinLevel stomp).
        if (tier >= 4 && mob instanceof AbstractSkeleton && !(mob instanceof WitherSkeleton)) {
            if (tuning.skeletonFlameBowChance() > 0.0f && random.nextFloat() < tuning.skeletonFlameBowChance()) {
                mob.getPersistentData().putBoolean(PENDING_SKELETON_FLAME_BOW_TAG, true);
            }
            if (tuning.skeletonPower5BowChance() > 0.0f && random.nextFloat() < tuning.skeletonPower5BowChance()) {
                mob.getPersistentData().putBoolean(PENDING_SKELETON_POWER5_BOW_TAG, true);
            }
        }

        // Zombies (and variants like Husks/Drowned) can naturally roll held tools/weapons in vanilla.
        // Tier tuning adds an additional chance when the main-hand is empty (Monsoon weights toward iron).
        if (mob.getMainHandItem().isEmpty()
            && random.nextFloat() < tuning.extraWeaponChance()
            && mob instanceof Zombie) {
            Item weapon = tier >= 3
                ? pickNightmareMeleeWeapon(random)
                : (tier >= 2 ? pickMonsoonMeleeWeapon(random) : pickEclipseMeleeWeapon(random));
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(weapon));
        }

        // Monsoon: skeletons / piglins can spawn with melee tools up to iron when main-hand is empty.
        if (tier >= 2
            && random.nextFloat() < tuning.extraWeaponChance()
            && mob.getMainHandItem().isEmpty()
            && (mob instanceof AbstractSkeleton || mob instanceof Piglin || mob instanceof PiglinBrute)) {
            Item weapon = tier >= 3 ? pickNightmareMeleeWeapon(random) : pickMonsoonMeleeWeapon(random);
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(weapon));
        }

        // If the mob spawned with no armor, add a tier-tuned chance to equip leather→iron (Nightmare: up to diamond) armor.
        if (random.nextFloat() < tuning.extraArmorChance() && isAllArmorEmpty(mob)) {
            maybeEquipArmorPiece(mob, random, tuning, tier, EquipmentSlot.HEAD);
            maybeEquipArmorPiece(mob, random, tuning, tier, EquipmentSlot.CHEST);
            maybeEquipArmorPiece(mob, random, tuning, tier, EquipmentSlot.LEGS);
            maybeEquipArmorPiece(mob, random, tuning, tier, EquipmentSlot.FEET);
        }

        // Hell (tier 4): armor-eligible mobs always spawn with at least one armor piece (covers missed extraArmorChance / per-slot rolls).
        // Inferno (tier 5): armor-eligible mobs always spawn with at least two armor pieces.
        // Torment (tier 6): armor-eligible mobs always spawn with a full armor set.
        if (tier == 4) {
            guaranteeMinimumArmorPieces(mob, random, tier, 1);
        } else if (tier == 6) {
            guaranteeMinimumArmorPieces(mob, random, tier, 4);
        } else if (tier >= 5) {
            guaranteeMinimumArmorPieces(mob, random, tier, 2);
        }

        // If the mob has gear, add a tier-tuned chance to enchant that gear.
        if (random.nextFloat() < tuning.extraEnchantChance() && hasAnyGear(mob)) {
            enchantSlotIfPresent(random, mob, tuning, EquipmentSlot.MAINHAND);
            enchantSlotIfPresent(random, mob, tuning, EquipmentSlot.OFFHAND);
            enchantSlotIfPresent(random, mob, tuning, EquipmentSlot.HEAD);
            enchantSlotIfPresent(random, mob, tuning, EquipmentSlot.CHEST);
            enchantSlotIfPresent(random, mob, tuning, EquipmentSlot.LEGS);
            enchantSlotIfPresent(random, mob, tuning, EquipmentSlot.FEET);
        }

        if (tier >= 2) {
            applyMonsoonPotionEffects(mob, random, tuning, level);
        }
    }

    private static void maybeSpawnNightmareOverworldVariant(
        ServerLevel level,
        ServerLevelAccessor serverLevelAccessor,
        MobSpawnEvent.FinalizeSpawn event,
        RandomSource random,
        TierTuning tuning,
        Mob anchor
    ) {
        // Pick one variant (equal weight). Spawn type EVENT so we don't re-trigger NATURAL-only logic.
        EntityType<? extends Mob> type = switch (random.nextInt(3)) {
            case 0 -> EntityType.STRAY;
            case 1 -> EntityType.HUSK;
            default -> EntityType.BOGGED;
        };

        Mob extra = type.create(level);
        if (extra == null) return;

        // Small offset so it doesn't intersect the anchor; keep Y-level same (vanilla spawn rules will still apply on finalizeSpawn).
        double dx = (random.nextDouble() - 0.5D) * 3.0D;
        double dz = (random.nextDouble() - 0.5D) * 3.0D;
        extra.moveTo(anchor.getX() + dx, anchor.getY(), anchor.getZ() + dz, anchor.getYRot(), anchor.getXRot());
        extra.finalizeSpawn(serverLevelAccessor, event.getDifficulty(), MobSpawnType.EVENT, null);
        // Because this mob is spawned manually, Forge's FinalizeSpawn event won't run for it.
        // Apply our tier tuning explicitly so Nightmare gear rolls affect it.
        applyTierGearAndEffects(extra, random, tuning, getCurrentTier(level), level);
        level.addFreshEntity(extra);
    }

    private static void maybeSpawnInfernoOverworldNetherMob(
        ServerLevel level,
        ServerLevelAccessor serverLevelAccessor,
        MobSpawnEvent.FinalizeSpawn event,
        RandomSource random,
        TierTuning tuning,
        Mob anchor
    ) {
        // Pick one Nether mob (equal weight). Spawn type EVENT so we don't re-trigger NATURAL-only logic.
        EntityType<? extends Mob> type = switch (random.nextInt(4)) {
            case 0 -> EntityType.PIGLIN;
            case 1 -> EntityType.PIGLIN_BRUTE;
            case 2 -> EntityType.HOGLIN;
            default -> EntityType.WITHER_SKELETON;
        };

        Mob extra = type.create(level);
        if (extra == null) return;

        double dx = (random.nextDouble() - 0.5D) * 4.0D;
        double dz = (random.nextDouble() - 0.5D) * 4.0D;
        extra.moveTo(anchor.getX() + dx, anchor.getY(), anchor.getZ() + dz, anchor.getYRot(), anchor.getXRot());
        extra.finalizeSpawn(serverLevelAccessor, event.getDifficulty(), MobSpawnType.EVENT, null);

        // Keep Nether mobs stable in the Overworld: force the "ImmuneToZombification" modifier where applicable.
        forceImmuneToZombification(extra, true);

        // Because this mob is spawned manually, Forge's FinalizeSpawn event won't run for it.
        // Apply tier tuning explicitly so it gets the same difficulty rolls.
        applyTierGearAndEffects(extra, random, tuning, getCurrentTier(level), level);
        level.addFreshEntity(extra);
    }

    private static void forceImmuneToZombification(Mob mob, boolean value) {
        // Piglins + Hoglins can zombify in the Overworld unless immune.
        // Use reflection to avoid mapping differences across Forge toolchains.
        if (!(mob instanceof Piglin) && !(mob instanceof PiglinBrute) && !(mob instanceof Hoglin)) return;
        try {
            var m = mob.getClass().getMethod("setImmuneToZombification", boolean.class);
            m.invoke(mob, value);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Apply diamond sword after vanilla finishes default gear (Wither Skeleton stone sword, etc.).
     * Runs at lowest priority so other listeners run first; then re-applies main hand for several ticks in case
     * vanilla or structures set equipment late.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onEntityJoinLevelForPendingDiamondSword(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof Mob mob)) return;
        boolean wantsDiamond = mob.getPersistentData().getBoolean(PENDING_DIAMOND_SWORD_TAG);
        boolean wantsNetherite = mob.getPersistentData().getBoolean(PENDING_NETHERITE_SWORD_TAG);
        boolean wantsSharpNetherite = mob.getPersistentData().getBoolean(PENDING_SHARPNESS_NETHERITE_SWORD_TAG);
        boolean wantsFlameBow = mob.getPersistentData().getBoolean(PENDING_SKELETON_FLAME_BOW_TAG);
        boolean wantsPower5Bow = mob.getPersistentData().getBoolean(PENDING_SKELETON_POWER5_BOW_TAG);
        if (!wantsDiamond && !wantsNetherite && !wantsSharpNetherite && !wantsFlameBow && !wantsPower5Bow) return;
        mob.getPersistentData().remove(PENDING_DIAMOND_SWORD_TAG);
        mob.getPersistentData().remove(PENDING_NETHERITE_SWORD_TAG);
        mob.getPersistentData().remove(PENDING_SHARPNESS_NETHERITE_SWORD_TAG);
        mob.getPersistentData().remove(PENDING_SKELETON_FLAME_BOW_TAG);
        mob.getPersistentData().remove(PENDING_SKELETON_POWER5_BOW_TAG);
        if (getCurrentTier(level) < 2) return;
        int tier = getCurrentTier(level);

        // Skeleton bow specials (tier 4+). If both hit, apply both enchantments on the same bow.
        if (tier >= 4 && (wantsFlameBow || wantsPower5Bow) && mob instanceof AbstractSkeleton && !(mob instanceof WitherSkeleton)) {
            scheduleTierItemStomp(
                mob,
                level,
                10,
                createSpecialSkeletonBow(level, wantsPower5Bow, wantsFlameBow),
                4,
                m -> m instanceof AbstractSkeleton && !(m instanceof WitherSkeleton)
            );
            return;
        }

        // Sword specials (Zombie + Wither Skeleton). Highest-tier wins.
        if (!(mob instanceof Zombie) && !(mob instanceof WitherSkeleton)) return;
        if (wantsSharpNetherite && tier >= 4) {
            scheduleTierItemStomp(
                mob,
                level,
                10,
                createSharpnessVNetheriteSword(level),
                4,
                m -> (m instanceof Zombie) || (m instanceof WitherSkeleton)
            );
        } else if (wantsNetherite && tier >= 3) {
            scheduleTierSwordStomp(mob, level, 10, Items.NETHERITE_SWORD, 3);
        } else if (wantsDiamond) {
            scheduleTierSwordStomp(mob, level, 10, Items.DIAMOND_SWORD, 2);
        }
    }

    /**
     * Torment (tier 6): any Ravager that spawns has a chance to gain an Evoker rider.
     * Uses EntityJoinLevel so it also applies to Ravagers created by raids/structures/commands/other mods.
     */
    @SubscribeEvent
    public void onEntityJoinLevelMaybeAddTormentRavagerEvokerRider(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof Ravager ravager)) return;

        if (getCurrentTier(level) != Tier.TORMENT.id) return;
        if (!ravager.isAlive()) return;
        if (!ravager.getPassengers().isEmpty()) return;

        TierTuning tuning = getTuning(level);
        if (tuning.tormentRavagerEvokerRiderChance() <= 0.0f) return;

        RandomSource random = level.getRandom();
        if (random.nextFloat() >= tuning.tormentRavagerEvokerRiderChance()) return;

        Evoker evoker = EntityType.EVOKER.create(level);
        if (evoker == null) return;

        evoker.moveTo(ravager.getX(), ravager.getY(), ravager.getZ(), ravager.getYRot(), ravager.getXRot());
        evoker.finalizeSpawn(level, level.getCurrentDifficultyAt(evoker.blockPosition()), MobSpawnType.EVENT, null);
        level.addFreshEntity(evoker);
        evoker.startRiding(ravager, true);
    }

    private static ItemStack createSharpnessVNetheriteSword(ServerLevel level) {
        ItemStack stack = new ItemStack(Items.NETHERITE_SWORD);
        var enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        stack.enchant(enchantments.getOrThrow(Enchantments.SHARPNESS), 5);
        return stack;
    }

    private static ItemStack createSpecialSkeletonBow(ServerLevel level, boolean power5, boolean flame) {
        ItemStack stack = new ItemStack(Items.BOW);
        var enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        if (power5) {
            stack.enchant(enchantments.getOrThrow(Enchantments.POWER), 5);
        }
        if (flame) {
            stack.enchant(enchantments.getOrThrow(Enchantments.FLAME), 1);
        }
        return stack;
    }

    private static void scheduleTierItemStomp(
        Mob mob,
        ServerLevel level,
        int ticksRemaining,
        ItemStack stack,
        int requiredTier,
        Predicate<Mob> allowedMob
    ) {
        if (ticksRemaining <= 0 || !mob.isAlive()) return;
        if (!(mob.level() instanceof ServerLevel mobLevel) || getCurrentTier(mobLevel) < requiredTier) return;
        if (allowedMob != null && !allowedMob.test(mob)) return;
        mob.setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
        if (ticksRemaining <= 1) return;
        level.getServer().execute(() -> scheduleTierItemStomp(mob, level, ticksRemaining - 1, stack, requiredTier, allowedMob));
    }

    /**
     * {@link SkeletonHorse} uses animal despawn rules (effectively never when idle), so modded Skeleton Knights
     * would leave persistent mounts. Tagged mounts use the same distance thresholds and spawn-rule gate as
     * {@link MobCategory#MONSTER} far-despawn behavior.
     */
    @SubscribeEvent
    public void onLivingTickDespawnSkeletonKnightHorse(LivingEvent.LivingTickEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        if (!(event.getEntity() instanceof SkeletonHorse horse)) return;
        if (!horse.getPersistentData().getBoolean(SKELETON_KNIGHT_HORSE_TAG)) return;
        if (!(horse.level() instanceof ServerLevel level)) return;

        maybeDespawnSkeletonKnightHorse(level, horse);
    }

    private static void maybeDespawnSkeletonKnightHorse(ServerLevel level, SkeletonHorse horse) {
        if (!level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) return;
        if (!level.shouldTickBlocksAt(horse.blockPosition().asLong())) return;
        if (horse.isPersistenceRequired()) return;
        if (horse.hasCustomName()) return;
        if (horse.isLeashed()) return;

        if (level.getDifficulty() == Difficulty.PEACEFUL) {
            discardSkeletonKnightMount(horse);
            return;
        }

        Player player = level.getNearestPlayer(horse, -1.0D);
        if (player == null) return;

        MobCategory cat = MobCategory.MONSTER;
        int despawnDist = cat.getDespawnDistance();
        int noDespawnDist = cat.getNoDespawnDistance();
        double despawnDistSq = (double) despawnDist * (double) despawnDist;
        double noDespawnDistSq = (double) noDespawnDist * (double) noDespawnDist;

        double d0 = player.distanceToSqr(horse);
        if (d0 < noDespawnDistSq) return;
        if (d0 <= despawnDistSq) return;

        if (horse.getRandom().nextInt(800) == 0) {
            discardSkeletonKnightMount(horse);
        }
    }

    private static void discardSkeletonKnightMount(SkeletonHorse horse) {
        for (Entity passenger : List.copyOf(horse.getPassengers())) {
            passenger.discard();
        }
        horse.discard();
    }

    private static void scheduleTierSwordStomp(Mob mob, ServerLevel level, int ticksRemaining, Item sword, int requiredTier) {
        if (ticksRemaining <= 0 || !mob.isAlive()) return;
        if (!(mob.level() instanceof ServerLevel mobLevel) || getCurrentTier(mobLevel) < requiredTier) return;
        if (!(mob instanceof Zombie) && !(mob instanceof WitherSkeleton)) return;
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(sword));
        if (ticksRemaining <= 1) return;
        level.getServer().execute(() -> scheduleTierSwordStomp(mob, level, ticksRemaining - 1, sword, requiredTier));
    }

    /** Monsoon (tier 2): each potion line is rolled independently so mobs can have any subset (e.g. only Speed, or Speed + Strength). */
    private static void applyMonsoonPotionEffects(Mob mob, RandomSource random, TierTuning tuning, ServerLevel level) {
        if (getCurrentTier(level) < 2) return;
        if (!(mob instanceof Monster)) return;

        int tier = getCurrentTier(level);
        // Visible effects only: Monsoon amp 0–1, Nightmare+ amp 0–2, Hell (tier 4) amp 0–3. Inferno/Torment match Nightmare cap.
        // (Hidden effects below remain amp 0 only.)
        int visibleAmpMaxInclusive = tier == 4 ? 3 : (tier >= 3 ? 2 : 1);

        float pv = tuning.potionVisibleChance();
        if (random.nextFloat() < pv) {
            mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, MobEffectInstance.INFINITE_DURATION, random.nextInt(visibleAmpMaxInclusive + 1), false, true, true));
        }
        if (random.nextFloat() < pv) {
            mob.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, MobEffectInstance.INFINITE_DURATION, random.nextInt(visibleAmpMaxInclusive + 1), false, true, true));
        }
        if (random.nextFloat() < pv) {
            mob.addEffect(new MobEffectInstance(MobEffects.HEALTH_BOOST, MobEffectInstance.INFINITE_DURATION, random.nextInt(visibleAmpMaxInclusive + 1), false, true, true));
        }
        if (random.nextFloat() < pv) {
            mob.addEffect(new MobEffectInstance(MobEffects.JUMP, MobEffectInstance.INFINITE_DURATION, random.nextInt(visibleAmpMaxInclusive + 1), false, true, true));
        }

        float ph = tuning.potionHiddenChance();
        if (random.nextFloat() < ph) {
            mob.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 0, true, false, false));
        }
        if (random.nextFloat() < ph) {
            mob.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 0, true, false, false));
        }
        if (random.nextFloat() < ph) {
            mob.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, MobEffectInstance.INFINITE_DURATION, 0, true, false, false));
        }

        // Inferno+ (tier 5+): additional hidden/no-particle effects, each rolled independently.
        // Uses the same hidden flags as the tier-hidden effects above (ambient=true, showParticles=false, showIcon=false).
        if (tier >= 5) {
            if (tuning.infernoOozingChance() > 0.0f && random.nextFloat() < tuning.infernoOozingChance()) {
                mob.addEffect(new MobEffectInstance(MobEffects.OOZING, MobEffectInstance.INFINITE_DURATION, 0, true, false, false));
            }
            if (tuning.infernoInfestationChance() > 0.0f && random.nextFloat() < tuning.infernoInfestationChance()) {
                mob.addEffect(new MobEffectInstance(MobEffects.INFESTED, MobEffectInstance.INFINITE_DURATION, 0, true, false, false));
            }
            if (tuning.infernoWindChargingChance() > 0.0f && random.nextFloat() < tuning.infernoWindChargingChance()) {
                mob.addEffect(new MobEffectInstance(MobEffects.WIND_CHARGED, MobEffectInstance.INFINITE_DURATION, 0, true, false, false));
            }
        }
    }

    private static Item pickEclipseMeleeWeapon(RandomSource random) {
        return pick(
            random,
            Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD,
            Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE,
            Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL
        );
    }

    /** Biased toward iron; still wood–iron only. */
    private static Item pickMonsoonMeleeWeapon(RandomSource random) {
        return pick(
            random,
            Items.IRON_SWORD, Items.IRON_SWORD, Items.IRON_SWORD,
            Items.STONE_SWORD, Items.WOODEN_SWORD,
            Items.IRON_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE,
            Items.IRON_SHOVEL, Items.STONE_SHOVEL, Items.WOODEN_SHOVEL
        );
    }

    /** Nightmare (tier 3): expands weapon pool up to diamond; still biased toward iron. */
    private static Item pickNightmareMeleeWeapon(RandomSource random) {
        return pick(
            random,
            Items.DIAMOND_SWORD,
            Items.IRON_SWORD, Items.IRON_SWORD, Items.IRON_SWORD,
            Items.STONE_SWORD, Items.WOODEN_SWORD,
            Items.DIAMOND_AXE,
            Items.IRON_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE,
            Items.DIAMOND_SHOVEL,
            Items.IRON_SHOVEL, Items.STONE_SHOVEL, Items.WOODEN_SHOVEL
        );
    }

    private static boolean isVanillaGearRollingMob(Mob mob) {
        // Keep Tier 1 "vanilla-like": only buff the same families that naturally roll gear.
        return mob instanceof Zombie
            || mob instanceof AbstractSkeleton
            || mob instanceof Piglin
            || mob instanceof PiglinBrute;
    }

    private static boolean isAllArmorEmpty(Mob mob) {
        return mob.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
            && mob.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
            && mob.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
            && mob.getItemBySlot(EquipmentSlot.FEET).isEmpty();
    }

    private static boolean hasAnyGear(Mob mob) {
        return !mob.getMainHandItem().isEmpty()
            || !mob.getOffhandItem().isEmpty()
            || !mob.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
            || !mob.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
            || !mob.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
            || !mob.getItemBySlot(EquipmentSlot.FEET).isEmpty();
    }

    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    /**
     * Guarantee a minimum number of armor pieces for armor-eligible mobs, using the same tier pools as {@link #maybeEquipArmorPiece}.
     * Does not remove/replace existing armor; only fills empty slots as needed.
     */
    private static void guaranteeMinimumArmorPieces(Mob mob, RandomSource random, int tier, int minPieces) {
        if (!isVanillaGearRollingMob(mob)) return;
        if (minPieces <= 0) return;

        int equipped = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (!mob.getItemBySlot(slot).isEmpty()) equipped++;
        }
        if (equipped >= minPieces) return;

        // Fill random empty slots until the minimum is reached or no slots remain.
        // (If the mob already has some armor, this preserves it and only adds missing pieces.)
        int needed = minPieces - equipped;
        for (int i = 0; i < needed; i++) {
            // Collect empty slots each iteration so we don't get stuck repeatedly picking filled slots.
            EquipmentSlot[] empty = java.util.Arrays.stream(ARMOR_SLOTS)
                .filter(s -> mob.getItemBySlot(s).isEmpty())
                .toArray(EquipmentSlot[]::new);
            if (empty.length == 0) return;

            EquipmentSlot slot = empty[random.nextInt(empty.length)];
            Item item = selectArmorItemForTier(random, tier, slot);
            if (item == null) return;
            mob.setItemSlot(slot, new ItemStack(item));
        }
    }

    private static void maybeEquipArmorPiece(Mob mob, RandomSource random, TierTuning tuning, int tier, EquipmentSlot slot) {
        if (!mob.getItemBySlot(slot).isEmpty()) return;

        // Keep it modest: each slot has its own roll.
        if (random.nextFloat() >= tuning.armorPieceChance()) return;

        Item item = selectArmorItemForTier(random, tier, slot);
        if (item == null) return;
        mob.setItemSlot(slot, new ItemStack(item));
    }

    /** Leather through iron (tier &lt; 3), + diamond (tier 3+), + netherite (tier 4 only), Torment (tier 6): diamond/netherite only. */
    private static Item selectArmorItemForTier(RandomSource random, int tier, EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> tier >= 6
                ? pick(random, Items.DIAMOND_HELMET, Items.NETHERITE_HELMET)
                : tier == 4
                    ? pick(random, Items.LEATHER_HELMET, Items.CHAINMAIL_HELMET, Items.IRON_HELMET, Items.DIAMOND_HELMET, Items.NETHERITE_HELMET)
                    : tier >= 3
                        ? pick(random, Items.LEATHER_HELMET, Items.CHAINMAIL_HELMET, Items.IRON_HELMET, Items.DIAMOND_HELMET)
                        : pick(random, Items.LEATHER_HELMET, Items.CHAINMAIL_HELMET, Items.IRON_HELMET);
            case CHEST -> tier >= 6
                ? pick(random, Items.DIAMOND_CHESTPLATE, Items.NETHERITE_CHESTPLATE)
                : tier == 4
                    ? pick(random, Items.LEATHER_CHESTPLATE, Items.CHAINMAIL_CHESTPLATE, Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE, Items.NETHERITE_CHESTPLATE)
                    : tier >= 3
                        ? pick(random, Items.LEATHER_CHESTPLATE, Items.CHAINMAIL_CHESTPLATE, Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE)
                        : pick(random, Items.LEATHER_CHESTPLATE, Items.CHAINMAIL_CHESTPLATE, Items.IRON_CHESTPLATE);
            case LEGS -> tier >= 6
                ? pick(random, Items.DIAMOND_LEGGINGS, Items.NETHERITE_LEGGINGS)
                : tier == 4
                    ? pick(random, Items.LEATHER_LEGGINGS, Items.CHAINMAIL_LEGGINGS, Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS, Items.NETHERITE_LEGGINGS)
                    : tier >= 3
                        ? pick(random, Items.LEATHER_LEGGINGS, Items.CHAINMAIL_LEGGINGS, Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS)
                        : pick(random, Items.LEATHER_LEGGINGS, Items.CHAINMAIL_LEGGINGS, Items.IRON_LEGGINGS);
            case FEET -> tier >= 6
                ? pick(random, Items.DIAMOND_BOOTS, Items.NETHERITE_BOOTS)
                : tier == 4
                    ? pick(random, Items.LEATHER_BOOTS, Items.CHAINMAIL_BOOTS, Items.IRON_BOOTS, Items.DIAMOND_BOOTS, Items.NETHERITE_BOOTS)
                    : tier >= 3
                        ? pick(random, Items.LEATHER_BOOTS, Items.CHAINMAIL_BOOTS, Items.IRON_BOOTS, Items.DIAMOND_BOOTS)
                        : pick(random, Items.LEATHER_BOOTS, Items.CHAINMAIL_BOOTS, Items.IRON_BOOTS);
            default -> null;
        };
    }

    @SafeVarargs
    private static <T> T pick(RandomSource random, T... options) {
        return options[random.nextInt(options.length)];
    }

    private static void enchantSlotIfPresent(RandomSource random, Mob mob, TierTuning tuning, EquipmentSlot slot) {
        ItemStack stack = mob.getItemBySlot(slot);
        if (stack.isEmpty()) return;
        if (!stack.isEnchantable()) return;
        if (stack.isEnchanted()) return;
        if (tuning.enchantLevelMaxInclusive() <= 0) return;

        int min = Math.max(0, tuning.enchantLevelMin());
        int max = Math.max(min, tuning.enchantLevelMaxInclusive());
        int level = min + random.nextInt((max - min) + 1);
        RegistryAccess registryAccess = mob.level().registryAccess();
        ItemStack enchanted = EnchantmentHelper.enchantItem(random, stack.copy(), level, registryAccess, Optional.empty());

        if (!ItemStack.matches(stack, enchanted)) {
            mob.setItemSlot(slot, enchanted);
        }
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
