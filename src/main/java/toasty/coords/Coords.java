package toasty.coords;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static net.minecraft.command.argument.BlockPosArgumentType.blockPos;
import static net.minecraft.command.argument.BlockPosArgumentType.getBlockPos;
import static net.minecraft.command.argument.DimensionArgumentType.dimension;
import static net.minecraft.command.argument.EntityArgumentType.getPlayers;
import static net.minecraft.command.argument.EntityArgumentType.players;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

@Environment(EnvType.SERVER)
public class Coords implements ModInitializer {

    private final Logger logger = LogManager.getLogger("Coords");
    private Connection connection;
    private List<Data> cache;

    private final String insertStatement = "INSERT OR REPLACE INTO coords(id, x, y, z, text, dimension, world, playersWithAccess, creator) VALUES(%s, %s, %s, %s, '%s', '%s', '%s', '%s', '%s')";

    static class Data {
        public int x;
        public int y;
        public int z;
        public int id;
        public long worldSeed;
        public String text;
        public String dimension;
        public List<String> playersWithAccess;
        public String creator;
    }

    @Override
    public void onInitialize() {
        logger.info("Initializing Coords");
        cache = new ArrayList<>();
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:%s".formatted(Paths.get(System.getProperty("user.dir"), "coords.db")));
            String createStatement = "CREATE TABLE IF NOT EXISTS coords(id INTEGER PRIMARY KEY, x INTEGER, y INTEGER, z INTEGER, text VARCHAR, dimension VARCHAR, world VARCHAR, playersWithAccess VARCHAR, creator VARCHAR)";
            connection.prepareStatement(createStatement).execute();
            String queryStatement = "SELECT * FROM coords";
            var resultSet = connection.prepareStatement(queryStatement).executeQuery();
            while (resultSet.next()){
                var newData = new Data();
                newData.id = resultSet.getInt("id");
                newData.x = resultSet.getInt("x");
                newData.y = resultSet.getInt("y");
                newData.z = resultSet.getInt("z");
                newData.text = resultSet.getString("text");
                newData.dimension = resultSet.getString("dimension");
                newData.worldSeed = Long.parseLong(resultSet.getString("world"));
                newData.playersWithAccess = new LinkedList<>(Arrays.asList(resultSet.getString("playersWithAccess").replace(" ", "").split(",")));
                newData.creator = resultSet.getString("creator");
                cache.add(newData);
            }
        } catch (SQLException | ClassNotFoundException e) {
            e.printStackTrace();
        }
        CommandRegistrationCallback.EVENT.register(this::registerCommands);
    }

    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, boolean dedicated){
        // coords
        var argBuilder =
            literal("coords")
                .then(
                    literal("add")
                        .then(
                            argument("pos", blockPos())
                                .then(
                                    argument("place", string())
                                        .executes(this::saveCoords)
                                )
                        )
                )
                .then(
                    literal("remove")
                        .then(
                            argument("place", string())
                                .suggests(new PlaceSuggestionProvider())
                                .executes(this::deleteCoords)
                        )
                )
                .then(
                    literal("list")
                        .then(
                            argument("dimension", dimension())
                                .executes(this::listCoords)
                        )
                        .executes(this::listCoords)
                )
                .then(
                    literal("share")
                        .then(
                            argument("players", players())
                                .then(
                                    argument("place", string())
                                        .suggests(new PlaceSuggestionProvider())
                                        .executes(this::shareCoords)
                                )
                        )
                )
                .then(
                    literal("unshare")
                        .then(
                            argument("players", players())
                                .then(
                                    argument("place", string())
                                        .suggests(new PlaceSuggestionProvider())
                                        .executes(this::unshareCoords)
                                )
                        )
                );
        dispatcher.register(argBuilder);
    }

    private int unshareCoords(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        var place = getString(ctx, "place");
        var players = getPlayers(ctx, "players");
        var uuids = players.stream().map(Entity::getUuidAsString).collect(Collectors.toList());
        var optionalData = cache.stream().filter(data -> data.text.toLowerCase().trim().equals(place.toLowerCase().trim())).findAny();
        if (optionalData.isEmpty())
        {
            ctx.getSource().sendFeedback(new LiteralText("share what"), false);
            return 0;
        }
        var data = optionalData.get();
        logger.info(data.playersWithAccess.getClass().getName());
        try {
            data.playersWithAccess.removeAll(uuids);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        try {
            connection.prepareStatement(insertStatement.formatted(data.id, data.x, data.y, data.z, data.text, data.dimension, data.worldSeed, String.join(",", data.playersWithAccess), data.creator)).execute();
            ctx.getSource().sendFeedback(new LiteralText("ok"), false);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private int shareCoords(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        var place = getString(ctx, "place");
        var players = getPlayers(ctx, "players");
        var uuids = players.stream().map(Entity::getUuidAsString).collect(Collectors.toList());
        var optionalData = cache.stream().filter(data -> data.text.toLowerCase().trim().equals(place.toLowerCase().trim())).findAny();
        if (optionalData.isEmpty())
        {
            ctx.getSource().sendFeedback(new LiteralText("share what"), false);
            return 0;
        }
        var data = optionalData.get();
        for (var player : uuids) {
            if (data.playersWithAccess.contains(player)) {
                continue;
            }

            data.playersWithAccess.add(player);
        }
        try {
            connection.prepareStatement(insertStatement.formatted(data.id, data.x, data.y, data.z, data.text, data.dimension, data.worldSeed, String.join(",", data.playersWithAccess), data.creator)).execute();
            ctx.getSource().sendFeedback(new LiteralText("ok"), false);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private int listCoords(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        String dimension;
        try {
            dimension = ctx.getArgument("dimension", Identifier.class).getPath();
        }
        catch (IllegalArgumentException e) {
            dimension = ctx.getSource().getPlayer().world.getRegistryKey().getValue().getPath();
        }
        var text = new LiteralText("");
        var hasData = false;
        for (var data : cache) {
            if (!(data.worldSeed == ctx.getSource().getWorld().getSeed() && data.dimension.equals(dimension) && data.playersWithAccess.contains(ctx.getSource().getPlayer().getUuidAsString())))
                continue;
            var coords = Texts.bracketed(new LiteralText("%s, %s, %s".formatted(data.x, data.y, data.z))).styled(style -> {
                var formatting = style.withColor(Formatting.GREEN);
                return formatting.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/execute in %s run tp @s %s %s %s".formatted(data.dimension, data.x, data.y, data.z))).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new LiteralText("Teleport")));
            });
            var otherStuff = new LiteralText(" §f-§ §6 %s § §f-§ §b %s §".formatted(data.text, data.dimension));
            text.append("\n");
            text.append(coords.append(otherStuff));
            hasData = true;
        }
        if (hasData)
            ctx.getSource().sendFeedback(text, false);
        else
            ctx.getSource().sendFeedback(new LiteralText("nothing"), false);
        return 0;
    }

    private int deleteCoords(CommandContext<ServerCommandSource> ctx) {
        for (var data : cache) {
            if (!data.text.toLowerCase().trim().equals(getString(ctx, "place").toLowerCase().trim())) {
                continue;
            }

            try {
                var isOwner = data.creator.toLowerCase().trim().equals(ctx.getSource().getPlayer().getUuidAsString());
                if (isOwner){
                    String removeStatement = "DELETE FROM coords WHERE text = '%s'";
                    connection.prepareStatement(removeStatement.formatted(data.text)).execute();
                    cache.remove(data);
                    ctx.getSource().sendFeedback(new LiteralText("deleted"), false);
                    return 0;
                }
                data.playersWithAccess.remove(ctx.getSource().getPlayer().getUuidAsString());
                connection.prepareStatement(insertStatement.formatted(data.id, data.x, data.y, data.z, data.text, data.dimension, data.worldSeed, String.join(",", data.playersWithAccess), data.creator)).execute();
                return 0;
            } catch (CommandSyntaxException | SQLException e) {
                e.printStackTrace();
            }
        }
        return 0;
    }

    private int saveCoords(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        var data = new Data();
        BlockPos pos = getBlockPos(ctx, "pos");
        var place = getString(ctx, "place");
        data.worldSeed = ctx.getSource().getWorld().getSeed();
        data.dimension = ctx.getSource().getPlayer().world.getRegistryKey().getValue().getPath();
        data.id = cache.size();
        data.text = place;
        data.x = pos.getX();
        data.y = pos.getY();
        data.z = pos.getZ();
        data.playersWithAccess = new ArrayList<>();
        data.playersWithAccess.add(ctx.getSource().getPlayer().getUuidAsString());
        data.creator = ctx.getSource().getPlayer().getUuidAsString();
        cache.add(data);
        try {
            connection.prepareStatement(insertStatement.formatted(data.id, data.x, data.y, data.z, data.text, data.dimension, data.worldSeed, String.join(",", data.playersWithAccess), data.creator)).execute();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        ctx.getSource().sendFeedback(new LiteralText("Added coords"), false);
        return 0;
    }

    class PlaceSuggestionProvider implements SuggestionProvider<ServerCommandSource> {

        @Override
        public CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
            try {
                var input = getString(context, "place");
                for (var data : cache) {
                    if (input.length() == 0){
                        cache.stream().filter(d -> {
                            try {
                                return d.playersWithAccess.contains(context.getSource().getPlayer().getUuidAsString());
                            } catch (CommandSyntaxException e) {
                                e.printStackTrace();
                                return false;
                            }
                        }).map(d -> d.text).forEach(builder::suggest);
                        break;
                    }
                    if (data.text.toLowerCase().trim().substring(0, input.length()).equals(input.toLowerCase().trim()) && data.playersWithAccess.contains(context.getSource().getPlayer().getUuidAsString())) {
                        builder.suggest(data.text);
                    }
                }
            } catch (Exception e) {
                // ignored
            }
            return builder.buildFuture();
        }
    }
}
