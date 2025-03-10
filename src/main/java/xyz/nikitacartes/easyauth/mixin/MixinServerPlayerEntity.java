package xyz.nikitacartes.easyauth.mixin;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;

import xyz.nikitacartes.easyauth.event.AuthEventHandler;
import xyz.nikitacartes.easyauth.storage.PlayerCache;
import xyz.nikitacartes.easyauth.utils.CarpetHelper;
import xyz.nikitacartes.easyauth.utils.PlayerAuth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static xyz.nikitacartes.easyauth.EasyAuth.*;
import static xyz.nikitacartes.easyauth.utils.EasyLogger.logInfo;

@Mixin(ServerPlayerEntity.class)
public class MixinServerPlayerEntity implements PlayerAuth {

    private final ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;

    // * 20 for 20 ticks in second
    @Unique
    private int kickTimer = config.main.kickTime * 20;

    @Final
    @Shadow
    public MinecraftServer server;

    /**
     * Teleports player to spawn or last location that is recorded.
     * Last location means the location before de-authentication.
     *
     * @param hide whether to teleport player to spawn (provided in config) or last recorded position
     */
    @Override
    public void hidePosition(boolean hide) {
        PlayerCache cache = playerCacheMap.get(this.getFakeUuid());
        if(config.experimental.debugMode)
            logInfo("Teleporting " + player.getName().asString() + (hide ? " to spawn." : " to original position."));
        if (hide) {
            // Saving position
            cache.lastLocation.dimension = player.getServerWorld();
            cache.lastLocation.position = player.getPos();
            cache.lastLocation.yaw = player.getYaw();
            cache.lastLocation.pitch = player.getPitch();

            // Teleports player to spawn
            player.teleport(
                    server.getWorld(RegistryKey.of(Registry.WORLD_KEY, new Identifier(config.worldSpawn.dimension))),
                    config.worldSpawn.x,
                    config.worldSpawn.y,
                    config.worldSpawn.z,
                    config.worldSpawn.yaw,
                    config.worldSpawn.pitch
            );
            return;
        }
        // Puts player to last cached position
        player.teleport(
                cache.lastLocation.dimension,
                cache.lastLocation.position.getX(),
                cache.lastLocation.position.getY(),
                cache.lastLocation.position.getZ(),
                cache.lastLocation.yaw,
                cache.lastLocation.pitch
        );
    }

    /**
     * Converts player uuid, to ensure player with "nAmE" and "NamE" get same uuid.
     * Both players are not allowed to play, since mod mimics Mojang behaviour.
     * of not allowing accounts with same names but different capitalization.
     *
     * @return converted UUID as string
     */
    @Override
    public String getFakeUuid() {
        // If server is in online mode online-mode UUIDs should be used
        assert server != null;
        if(server.isOnlineMode() && this.isUsingMojangAccount() && !config.experimental.forcedOfflineUuids)
            return player.getUuidAsString();
        /*
            Lower case is used for Player and PlAyEr to get same UUID (for password storing)
            Mimicking Mojang behaviour, where players cannot set their name to
            ExAmple if Example is already taken.
        */
        String playername = player.getGameProfile().getName().toLowerCase();
        return PlayerEntity.getOfflinePlayerUuid(playername).toString();

    }

    /**
     * Sets the authentication status of the player
     * and hides coordinates if needed.
     *
     * @param authenticated whether player should be authenticated
     */
    @Override
    public void setAuthenticated(boolean authenticated) {
        PlayerCache playerCache = playerCacheMap.get(this.getFakeUuid());
        playerCache.isAuthenticated = authenticated;

        player.setInvulnerable(!authenticated && config.experimental.playerInvulnerable);
        player.setInvisible(!authenticated && config.experimental.playerInvisible);

        // Teleporting player (hiding / restoring position)
        if(config.main.spawnOnJoin)
            this.hidePosition(!authenticated);

        if(authenticated) {
            kickTimer = config.main.kickTime * 20;
            // Updating blocks if needed (if portal rescue action happened)
            if(playerCache.wasInPortal) {
                World world = player.getEntityWorld();
                BlockPos pos = player.getBlockPos();

                // Sending updates to portal blocks
                // This is technically not needed, but it cleans the "messed portal" on the client
                world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
                world.updateListeners(pos.up(), world.getBlockState(pos.up()), world.getBlockState(pos.up()), 3);
            }
        }
    }

    /**
     * Gets the text which tells the player
     * to login or register, depending on account status.
     *
     * @return LiteralText with appropriate string (login or register)
     */
    @Override
    public Text getAuthMessage() {
        final PlayerCache cache = playerCacheMap.get(((PlayerAuth) player).getFakeUuid());
        if(!config.main.enableGlobalPassword && cache.password.isEmpty())
            return new TranslatableText("text.easyauth.notAuthenticated").append("\n").append(new TranslatableText("text.easyauth.registerRequired"));
        return new TranslatableText("text.easyauth.notAuthenticated").append("\n").append(new TranslatableText("text.easyauth.loginRequired"));
    }

    /**
     * Checks whether player can skip authentication process.
     *
     * @return true if can skip authentication process, otherwise false
     */
    @Override
    public boolean canSkipAuth() {
        return (FabricLoader.getInstance().isModLoaded("carpet") && CarpetHelper.isPlayerFake(this.player)) || (isUsingMojangAccount() && config.main.premiumAutologin);
    }

    /**
     * Whether the player is using the mojang account.
     *
     * @return true if they are  using mojang account, otherwise false
     */
    @Override
    public boolean isUsingMojangAccount() {
        return mojangAccountNamesCache.contains(player.getGameProfile().getName().toLowerCase());
    }

    /**
     * Checks whether player is authenticated.
     *
     * @return false if player is not authenticated, otherwise true.
     */
    @Override
    public boolean isAuthenticated() {
        String uuid = ((PlayerAuth) player).getFakeUuid();
        return  this.canSkipAuth() || (playerCacheMap.containsKey(uuid) && playerCacheMap.get(uuid).isAuthenticated);
    }

    @Inject(method = "playerTick()V", at = @At("HEAD"), cancellable = true)
    private void playerTick(CallbackInfo ci) {
        if(!this.isAuthenticated()) {
            // Checking player timer
            if(kickTimer <= 0 && player.networkHandler.getConnection().isOpen()) {
                player.networkHandler.disconnect(new TranslatableText("text.easyauth.timeExpired"));
            }
            else {
                // Sending authentication prompt every 10 seconds
                if(kickTimer % 200 == 0)
                    player.sendMessage(this.getAuthMessage(), false);
                --kickTimer;
            }
            ci.cancel();
        }
    }

    // Player item dropping
    @Inject(method = "dropSelectedItem(Z)Z", at = @At("HEAD"), cancellable = true)
    private void dropSelectedItem(boolean dropEntireStack, CallbackInfoReturnable<Boolean> cir) {
        ActionResult result = AuthEventHandler.onDropItem(player);

        if (result == ActionResult.FAIL) {
            cir.setReturnValue(false);
        }
    }
}