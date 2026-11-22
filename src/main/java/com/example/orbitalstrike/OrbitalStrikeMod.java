package com.example.orbitalstrike;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.Vec3ArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class OrbitalStrikeMod implements ModInitializer {
    private static final String STRIKE_KEY = "OrbitalStrikeType";
    private static UUID orbitalOwnerUuid = null;
    private static int nukeRingCount = 6;

    private static ServerWorld stasisWorld = null;
    private static Vec3d stasisPos = null;

    private record PendingStrike(ServerWorld world, Vec3d pos, String type, int ticksRemaining) {}
    private static final List<PendingStrike> PENDING_STRIKES = new ArrayList<>();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS;
            }

            ItemStack stack = serverPlayer.getStackInHand(hand);
            if (!stack.isOf(Items.FISHING_ROD)) {
                return ActionResult.PASS;
            }

            String strikeType = getStrikeType(stack);
            if (strikeType == null) {
                return ActionResult.PASS;
            }

            FishingBobberEntity bobber = serverPlayer.fishHook;
            if (bobber != null && !bobber.isRemoved()) {
                if (strikeType.equals("stasis")) {
                    if (stasisPos == null || stasisWorld == null) {
                        return ActionResult.PASS;
                    }

                    EquipmentSlot slot = (hand == Hand.MAIN_HAND) ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
                    stack.damage(stack.getMaxDamage(), serverPlayer, slot);
                    if (!stack.isEmpty()) {
                        stack.decrement(1);
                    }

                    serverPlayer.teleport(stasisWorld, stasisPos.x, stasisPos.y, stasisPos.z, Set.of(), serverPlayer.getYaw(), serverPlayer.getPitch(), false);
                    return ActionResult.PASS;
                }

                EquipmentSlot slot = (hand == Hand.MAIN_HAND) ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
                stack.damage(stack.getMaxDamage(), serverPlayer, slot);
                if (!stack.isEmpty()) {
                    stack.decrement(1);
                }

                ServerWorld targetWorld = serverPlayer.getEntityWorld();
                Vec3d targetPos = getLookTarget(serverPlayer, 150.0);

                if (orbitalOwnerUuid != null && !orbitalOwnerUuid.equals(serverPlayer.getUuid())) {
                    ServerPlayerEntity owner = serverPlayer.getEntityWorld().getServer().getPlayerManager().getPlayer(orbitalOwnerUuid);
                    if (owner != null) {
                        targetWorld = owner.getEntityWorld();
                        targetPos = getLookTarget(owner, 150.0);
                    }
                }

                PENDING_STRIKES.add(new PendingStrike(targetWorld, targetPos, strikeType, 20));
            }

            return ActionResult.PASS;
        });

        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
    }

    private static Vec3d getLookTarget(ServerPlayerEntity player, double maxDistance) {
        HitResult hit = player.raycast(maxDistance, 0.0f, false);
        if (hit.getType() != HitResult.Type.MISS) {
            return hit.getPos();
        }
        return new Vec3d(player.getX(), player.getY(), player.getZ());
    }

    private void onServerTick(MinecraftServer server) {
        if (PENDING_STRIKES.isEmpty()) return;

        Iterator<PendingStrike> iterator = PENDING_STRIKES.iterator();
        List<PendingStrike> nextTick = new ArrayList<>();

        while (iterator.hasNext()) {
            PendingStrike strike = iterator.next();
            iterator.remove();

            if (strike.ticksRemaining() > 1) {
                nextTick.add(new PendingStrike(strike.world(), strike.pos(), strike.type(), strike.ticksRemaining() - 1));
            } else {
                if (strike.type().equals("nuke")) {
                    executeNuke(strike.world(), strike.pos());
                } else if (strike.type().equals("stab")) {
                    executeStabShot(strike.world(), strike.pos());
                }
            }
        }

        PENDING_STRIKES.addAll(nextTick);
    }

    private static void executeNuke(ServerWorld world, Vec3d pos) {
        double baseDropY = Math.min(world.getTopYInclusive() - 5.0, pos.y + 68.0);
        Random random = world.getRandom();

        // Ring 0 (Core Ring): Super-dense solid red ring of ~76 overlapping TNT blocks with an open center hole
        int coreCount = 76;
        double coreRadius = 6.2;
        for (int i = 0; i < coreCount; i++) {
            double angle = (2.0 * Math.PI * i) / coreCount;
            double spawnX = pos.x + Math.cos(angle) * coreRadius;
            double spawnZ = pos.z + Math.sin(angle) * coreRadius;
            double velX = Math.cos(angle) * 0.08;
            double velZ = Math.sin(angle) * 0.08;
            spawnOrbitalTnt(world, spawnX, baseDropY, spawnZ, velX, -1.25, velZ, 56);
        }

        // Outer concentric rings (Rings 1 to nukeRingCount - 1)
        for (int r = 1; r < nukeRingCount; r++) {
            double radius = 6.2 + (r * 7.5);
            int count = 36 + (r * 14);
            double ringY = Math.min(world.getTopYInclusive() - 4.0, baseDropY + (r * 2.5));
            int fuse = 56 + (r * 2);

            for (int i = 0; i < count; i++) {
                double angle = (2.0 * Math.PI * i) / count + (r * 0.13);
                double jitterR = radius + (random.nextDouble() - 0.5) * 1.1;
                double spawnX = pos.x + Math.cos(angle) * jitterR;
                double spawnZ = pos.z + Math.sin(angle) * jitterR;

                double radialSpeed = 0.08 + (r * 0.075);
                double velX = Math.cos(angle) * radialSpeed;
                double velZ = Math.sin(angle) * radialSpeed;

                spawnOrbitalTnt(world, spawnX, ringY, spawnZ, velX, -1.25, velZ, fuse);
            }
        }
    }

    private static void executeStabShot(ServerWorld world, Vec3d pos) {
        // Launch a high-velocity vertical column of primed TNT charges that detonate sequentially down to bedrock
        double skyY = Math.min(world.getTopYInclusive() - 3.0, pos.y + 42.0);
        int totalCharges = 55;

        for (int i = 0; i < totalCharges; i++) {
            // Stagger fuse from 14 ticks (surface impact) up to 48 ticks (bedrock floor)
            int fuse = 14 + (i / 2);
            // High downward speed so deeper charges punch straight through the crater created by earlier charges
            double downwardVel = -2.8 - (i * 0.06);
            double spawnY = Math.min(world.getTopYInclusive() - 2.0, skyY + (i * 0.45));

            // Spawn a tight 2-TNT cluster per depth step so it punches through stone & deepslate cleanly
            spawnOrbitalTnt(world, pos.x, spawnY, pos.z, 0.0, downwardVel, 0.0, fuse);
            if (i % 2 == 0) {
                spawnOrbitalTnt(world, pos.x + 0.15, spawnY + 0.2, pos.z - 0.15, 0.0, downwardVel, 0.0, fuse + 1);
            }
        }
    }

    private static void spawnOrbitalTnt(ServerWorld world, double x, double y, double z, double vx, double vy, double vz, int fuse) {
        TntEntity tnt = new TntEntity(world, x, y, z, null);
        tnt.setFuse(fuse);
        tnt.setVelocity(vx, vy, vz);
        world.spawnEntity(tnt);
    }
    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("orbital")
            .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
            .then(CommandManager.literal("give")
                .then(CommandManager.literal("nuke")
                    .executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                        player.getInventory().insertStack(createOrbitalRod("nuke"));
                        ctx.getSource().sendFeedback(() -> Text.literal("Given Nuke Rod."), false);
                        return 1;
                    }))
                .then(CommandManager.literal("stab")
                    .executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                        player.getInventory().insertStack(createOrbitalRod("stab"));
                        ctx.getSource().sendFeedback(() -> Text.literal("Given Stab Rod."), false);
                        return 1;
                    }))
                .then(CommandManager.literal("stasis")
                    .executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                        player.getInventory().insertStack(createOrbitalRod("stasis"));
                        ctx.getSource().sendFeedback(() -> Text.literal("Given Stasis Rod."), false);
                        return 1;
                    }))
                .then(CommandManager.literal("all")
                    .executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                        player.getInventory().insertStack(createOrbitalRod("nuke"));
                        player.getInventory().insertStack(createOrbitalRod("stab"));
                        player.getInventory().insertStack(createOrbitalRod("stasis"));
                        ctx.getSource().sendFeedback(() -> Text.literal("Given Nuke Rod, Stab Rod, and Stasis Rod."), false);
                        return 1;
                    })))
            .then(CommandManager.literal("stasis")
                .then(CommandManager.argument("pos", Vec3ArgumentType.vec3())
                    .executes(ctx -> {
                        stasisPos = Vec3ArgumentType.getVec3(ctx, "pos");
                        stasisWorld = ctx.getSource().getWorld();
                        ctx.getSource().sendFeedback(
                            () -> Text.literal("Set Stasis Rod destination to " + (int) stasisPos.x + ", " + (int) stasisPos.y + ", " + (int) stasisPos.z + "."),
                            true
                        );
                        return 1;
                    })))
            .then(CommandManager.literal("ownership")
                .then(CommandManager.literal("clear")
                    .executes(ctx -> {
                        orbitalOwnerUuid = null;
                        ctx.getSource().sendFeedback(() -> Text.literal("Cleared orbital ownership."), true);
                        return 1;
                    }))
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .executes(ctx -> {
                        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                        orbitalOwnerUuid = target.getUuid();
                        ctx.getSource().sendFeedback(
                            () -> Text.literal("Orbital ownership set to " + target.getName().getString() + "."),
                            true
                        );
                        return 1;
                    })))
            .then(CommandManager.literal("settings")
                .then(CommandManager.literal("nuke")
                    .then(CommandManager.argument("rings", IntegerArgumentType.integer(1, 25))
                        .executes(ctx -> {
                            nukeRingCount = IntegerArgumentType.getInteger(ctx, "rings");
                            ctx.getSource().sendFeedback(
                                () -> Text.literal("Set Nuke Rod ring count to " + nukeRingCount + "."),
                                true
                            );
                            return 1;
                        }))))
        );
    }

    private static ItemStack createOrbitalRod(String type) {
        ItemStack rod = new ItemStack(Items.FISHING_ROD);

        NbtCompound nbt = new NbtCompound();
        nbt.putString(STRIKE_KEY, type);
        rod.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        rod.set(DataComponentTypes.MAX_DAMAGE, 4);
        rod.set(DataComponentTypes.DAMAGE, 0);

        switch (type) {
            case "nuke" -> rod.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Nuke Rod"));
            case "stab" -> rod.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Stab Rod"));
            case "stasis" -> rod.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Stasis Rod"));
        }

        return rod;
    }

    private static String getStrikeType(ItemStack stack) {
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customData == null) return null;
        NbtCompound nbt = customData.copyNbt();
        return nbt.contains(STRIKE_KEY) ? nbt.getString(STRIKE_KEY).orElse(null) : null;
    }
}
