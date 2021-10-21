package toasty.coords;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static net.minecraft.command.argument.BlockPosArgumentType.blockPos;
import static net.minecraft.command.argument.BlockPosArgumentType.getBlockPos;
import static net.minecraft.command.argument.DimensionArgumentType.dimension;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

@Environment(EnvType.CLIENT)
public class CoordsClient implements ClientModInitializer {

    static class Data {
        public int x;
        public int y;
        public int z;
        public int id;
        public long worldSeed;
        public String text;
        public String dimension;
    }

    public final Logger logger = LogManager.getLogger("Coords");
    public Connection connection;
    public List<Data> cache;

    public final String createStatement = "CREATE TABLE IF NOT EXISTS coords(id INTEGER PRIMARY KEY, x INTEGER, y INTEGER, z INTEGER, text VARCHAR, dimension VARCHAR, world INTEGER)";
    public final String insertStatement = "INSERT OR REPLACE INTO coords(id, x, y, z, text, dimension, world) VALUES(%s, %s, %s, %s, '%s', '%s', %s)";
    public final String removeStatement = "DELETE FROM coords WHERE text = '%s'";
    public final String queryStatement = "SELECT * FROM coords";

    @Override
    public void onInitializeClient() {
        logger.info("Initializing Coords");
/*
        cache = new ArrayList<>();
        try {
            // Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:%s".formatted(Paths.get(System.getProperty("user.dir"), "coords.db")));
            connection.prepareStatement(createStatement).execute();
            var resultSet = connection.prepareStatement(queryStatement).executeQuery();
            while (resultSet.next()){
                var newData = new Data();
                newData.id = resultSet.getInt("id");
                newData.x = resultSet.getInt("x");
                newData.y = resultSet.getInt("y");
                newData.z = resultSet.getInt("z");
                newData.text = resultSet.getString("text");
                newData.dimension = resultSet.getString("dimension");
                newData.worldSeed = resultSet.getInt("world");
                cache.add(newData);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        CommandRegistrationCallback.EVENT.register(this::registerCommands);
*/
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
                );
        dispatcher.register(argBuilder);
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
        for (var data : cache) {
            if (!(data.worldSeed == ctx.getSource().getWorld().getSeed() && data.dimension.equals(dimension)))
                continue;
            var coords = Texts.bracketed(new LiteralText("%s, %s, %s".formatted(data.x, data.y, data.z))).styled(style -> {
                var formatting = style.withColor(Formatting.GREEN);
                return formatting.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/execute in %s run tp @s %s %s %s".formatted(data.dimension, data.x, data.y, data.z))).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new LiteralText("Teleport")));
            });
            var otherStuff = new LiteralText(" §f-§ §6 %s § §f-§ §b %s §".formatted(data.text, data.dimension));
            text.append("\n");
            text.append(coords.append(otherStuff));
        }
        ctx.getSource().sendFeedback(text, false);
        return 0;
    }

    private int deleteCoords(CommandContext<ServerCommandSource> ctx) {
        cache.removeIf(data -> data.text.toLowerCase().trim().equals(getString(ctx, "place").toLowerCase().trim()));
        try {
            connection.prepareStatement(removeStatement.formatted(getString(ctx, "place"))).execute();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        ctx.getSource().sendFeedback(new LiteralText("Removed coords"), false);
        return 0;
    }

    private int saveCoords(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        var data = new Data();
        var pos = getBlockPos(ctx, "pos");
        var place = getString(ctx, "place");
        data.worldSeed = ctx.getSource().getWorld().getSeed();
        data.dimension = ctx.getSource().getPlayer().world.getRegistryKey().getValue().getPath();
        data.id = cache.size();
        data.text = place;
        data.x = pos.getX();
        data.y = pos.getY();
        data.z = pos.getZ();
        cache.add(data);
        try {
            connection.prepareStatement(insertStatement.formatted(data.id, data.x, data.y, data.z, data.text, data.dimension, data.worldSeed)).execute();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        ctx.getSource().sendFeedback(new LiteralText("Added coords"), false);
        return 0;
    }

    class PlaceSuggestionProvider implements SuggestionProvider<ServerCommandSource> {

        @Override
        public CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
            var input = getString(context, "place");
            for (var data : cache) {
                if (data.text.toLowerCase().trim().substring(0, input.length()).equals(input.toLowerCase().trim()))
                    builder.suggest(data.text);
            }
            return builder.buildFuture();
        }
    }
}
