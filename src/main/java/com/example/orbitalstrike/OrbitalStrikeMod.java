package com.example.orbitalstrike;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class OrbitalStrikeMod implements ModInitializer {
    private static final String STRIKE_KEY = "OrbitalStrikeType";
    private static UUID orbitalOwnerUuid = null;
    private static int nukeRingCount = 5;

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

                PENDING_STRIKES.add(new PendingStrike(targetWorld, targetPos, strikeType, 25));
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
        double dropY = Math.min(world.getTopYInclusive() - 5.0, pos.y + 55.0);
        int fuseTicks = 52;

        spawnOrbitalTnt(world, pos.x, dropY, pos.z, 0.0, 0.0, fuseTicks);

        for (int r = 0; r < nukeRingCount; r++) {
            double radius = 4.5 + (r * 6.0);
            int count = 28 + (r * 8);

            for (int i = 0; i < count; i++) {
                double angle = (2.0 * Math.PI * i) / count;
                double spawnX = pos.x + Math.cos(angle) * radius;
                double spawnZ = pos.z + Math.sin(angle) * radius;

                double velX = Math.cos(angle) * (radius * 0.012);
                double velZ = Math.sin(angle) * (radius * 0.012);

                spawnOrbitalTnt(world, spawnX, dropY, spawnZ, velX, velZ, fuseTicks);
            }
        }
    }

    private static void spawnOrbitalTnt(ServerWorld world, double x, double y, double z, double vx, double vz, int fuse) {
        TntEntity tnt = new TntEntity(world, x, y, z, null);
        tnt.setFuse(fuse);
        tnt.setVelocity(vx, -1.1, vz);
        world.spawnEntity(tnt);
    }

    private static void executeStabShot(ServerWorld world, Vec3d pos) {
        int startY = (int) pos.y + 5;
        int minY = world.getBottomY();
        Random random = world.getRandom();

        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int y = startY; y >= minY; y--) {
            double wobbleX = (random.nextDouble() - 0.5) * 0.6;
            double wobbleZ = (random.nextDouble() - 0.5) * 0.6;

            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    double distSq = (dx + wobbleX) * (dx + wobbleX) + (dz + wobbleZ) * (dz + wobbleZ);
                    double maxRadiusSq = 10.2 + (random.nextDouble() - 0.5) * 4.2;

                    if (distSq <= maxRadiusSq) {
                        mutable.set((int) Math.floor(pos.x) + dx, y, (int) Math.floor(pos.z) + dz);
                        BlockState state = world.getBlockState(mutable);
                        if (!state.isAir() && state.getHardness(world, mutable) >= 0) {
                            if (!state.getFluidState().isEmpty()) {
                                world.setBlockState(mutable, Blocks.AIR.getDefaultState(), 3);
                            } else {
                                world.breakBlock(mutable, false);
                            }
                        }
                    }
                }
            }
        }

        for (int y = startY; y >= minY; y -= 8) {
            world.createExplosion(null, pos.x, y, pos.z, 4.0f, false, World.ExplosionSourceType.NONE);
        }
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
