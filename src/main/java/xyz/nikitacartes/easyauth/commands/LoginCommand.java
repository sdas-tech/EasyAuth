package xyz.nikitacartes.easyauth.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.TranslatableText;
import xyz.nikitacartes.easyauth.utils.AuthHelper;
import xyz.nikitacartes.easyauth.utils.PlayerAuth;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;
import static xyz.nikitacartes.easyauth.EasyAuth.*;

public class LoginCommand {

    public static void registerCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
        // Registering the "/login" command
        dispatcher.register(literal("login")
                .then(argument("password", word())
                        .executes(ctx -> login(ctx.getSource(), getString(ctx, "password")) // Tries to authenticate user
                        ))
                .executes(ctx -> {
                    ctx.getSource().getPlayer().sendMessage(new TranslatableText("text.easyauth.enterPassword"), false);
                    return 0;
                }));
    }

    // Method called for checking the password
    private static int login(ServerCommandSource source, String pass) throws CommandSyntaxException {
        // Getting the player who send the command
        ServerPlayerEntity player = source.getPlayer();
        String uuid = ((PlayerAuth) player).getFakeUuid();
        if (((PlayerAuth) player).isAuthenticated()) {
            player.sendMessage(new TranslatableText("text.easyauth.alreadyAuthenticated"), false);
            return 0;
        }
        // Putting rest of the command in different thread to avoid lag spikes
        THREADPOOL.submit(() -> {
            int maxLoginTries = config.main.maxLoginTries;
            AuthHelper.PasswordOptions passwordResult = AuthHelper.checkPassword(uuid, pass.toCharArray());

            if(playerCacheMap.get(uuid).loginTries >= maxLoginTries && maxLoginTries != -1) {
                player.networkHandler.disconnect(new TranslatableText("text.easyauth.loginTriesExceeded"));
                return;
            }
            else if(passwordResult == AuthHelper.PasswordOptions.CORRECT) {
                player.sendMessage(new TranslatableText("text.easyauth.successfullyAuthenticated"), false);
                ((PlayerAuth) player).setAuthenticated(true);
                return;
            }
            else if(passwordResult == AuthHelper.PasswordOptions.NOT_REGISTERED) {
                player.sendMessage(new TranslatableText("text.easyauth.registerRequired"), false);
                return;
            }
            // Kicking the player out
            else if(maxLoginTries == 1) {
                player.networkHandler.disconnect(new TranslatableText("text.easyauth.wrongPassword"));
                return;
            }
            // Sending wrong pass message
            player.sendMessage(new TranslatableText("text.easyauth.wrongPassword"), false);
            // ++ the login tries
            playerCacheMap.get(uuid).loginTries += 1;
        });
        return 0;
    }
}
