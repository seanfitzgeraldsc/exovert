package com.cyngn.exovert;


import com.cyngn.exovert.generate.entity.TableGenerator;
import com.cyngn.exovert.generate.entity.UDTGenerator;
import com.cyngn.exovert.generate.storage.DalGenerator;
import com.cyngn.exovert.util.MetaData;
import com.cyngn.exovert.util.Udt;
import com.cyngn.exovert.util.VertxRef;
import com.cyngn.vertx.async.Action;
import com.cyngn.vertx.async.promise.Promise;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.KeyspaceMetadata;
import com.englishtown.vertx.cassandra.impl.DefaultCassandraSession;
import com.englishtown.vertx.cassandra.impl.JsonCassandraConfigurator;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static java.util.Arrays.asList;

/**
 * Runs the generation tool.
 *
 * @author truelove@cyngn.com (Jeremy Truelove) 5/15/15
 */
public class Runner {

    private static Vertx vertx;

    // command line params
    private OptionSpec<String> namespace;
    private OptionSpec<String> db;
    private OptionSpec<String> keyspace;
    private OptionSpec<String> out;
    private OptionSpec preview;
    private OptionSpec create;
    private OptionSpec rest;
    private OptionSpec help;

    // the primary commands users can select
    private Map<OptionSpec<?>, Action> actions;
    private OptionParser parser;

    private DefaultCassandraSession session;
    private OptionSet optionSet;

    private Runner() {
        vertx = Vertx.vertx();
        parser = getParser();

        actions = new HashMap<>();
        actions.put(create, this::create);
        actions.put(preview, this::preview);
        actions.put(help, () -> {
            try {
                parser.printHelpOn(System.out);
            } catch (Exception ex) {
            }
        });
    }

    private void create() {
        init(false);
        execute(session.getCluster().getMetadata().getKeyspace(MetaData.instance.getKeyspace()));
    }

    private void preview() {
        init(true);
        execute(session.getCluster().getMetadata().getKeyspace(MetaData.instance.getKeyspace()));
    }

    private void execute(KeyspaceMetadata ksm) {
        try { UDTGenerator.generate(ksm.getUserTypes()); }
        catch (IOException e) { e.printStackTrace(); }

        try { TableGenerator.generate(ksm.getTables()); }
        catch (IOException ex) { ex.printStackTrace(); }

        try { DalGenerator.generate(ksm.getTables()); }
        catch (IOException e) { e.printStackTrace(); }
    }

    private void init(boolean isPreview) {
        String outDir = out.value(optionSet);
        String keySpace = keyspace.value(optionSet);
        String nameSpace = namespace.value(optionSet);

        KeyspaceMetadata ksm = session.getCluster().getMetadata().getKeyspace(keySpace);

        Udt.instance.init(ksm);
        MetaData.instance.init(nameSpace, keySpace, !isPreview ? outDir : null);
        VertxRef.instance.init(vertx);
    }

    /**
     * Setup DB connections.
     */
    private void initCassandra(Consumer<Boolean> onComplete) {
        String dbHost = db.value(optionSet);

        JsonObject config = new JsonObject()
            .put("seeds", new JsonArray(Arrays.asList(dbHost)))
            .put("query", new JsonObject().put("consistency", "LOCAL_QUORUM"))
            .put("reconnect", new JsonObject().put("name", "exponential").put("base_delay", 1000).put("max_delay", 1000));

        System.out.println("Cassandra config to use:" + config.encode());

        JsonCassandraConfigurator configurator = new JsonCassandraConfigurator(config);
        session = new DefaultCassandraSession(Cluster.builder(), configurator, vertx);

        onComplete.accept(true);
    }

    /**
     * Builds the command line parser
     */
    private OptionParser getParser() {
        OptionParser parser = new OptionParser();
        create = parser.acceptsAll(asList("create", "c"), "create the basic service infrastructure");
        preview = parser.acceptsAll(asList("preview", "p"), "output all the java files to the console, don't create files");
        keyspace = parser.acceptsAll(asList("keyspace", "k"), "the keyspace to read from").requiredIf(create, preview)
                .withRequiredArg().ofType(String.class);
        namespace = parser.acceptsAll(asList("namespace", "n"), "the namespace to create java classes in").requiredIf(create, preview)
                .withRequiredArg().ofType(String.class);
        db = parser.acceptsAll(asList("db", "d"), "the db host to connect to").requiredIf(create, preview)
                .withRequiredArg().ofType(String.class);
        out = parser.acceptsAll(asList("out", "o"), "the output dir to place files in").requiredIf(create).withRequiredArg().ofType(String.class);
        rest = parser.acceptsAll(asList("rest", "r"), "generate the REST API for the scheme");
        help = parser.accepts("help", "shows this message");
        return parser;
    }

    /**
     * Handle parsing the command line args and directing the app path
     */
    private void run(String [] args) throws IOException {
        optionSet = parser.parse(args);

        OptionSpec<?> invalidSelection = null;
        Map.Entry<OptionSpec<?>, Action> spec = null;

        // figure out which primary action they selected and make sure they didn't choose two of them
        for(Map.Entry<OptionSpec<?>, Action> entry: actions.entrySet()) {
            if(optionSet.has(entry.getKey())) {
                if(spec != null) {
                    invalidSelection = entry.getKey();
                    break;
                }
                spec = entry;
            }
        }

        if(invalidSelection != null) {
            System.out.println("Competing options selected - option1: " + spec.getKey() + " option2: " +
                    invalidSelection + "\n");
            parser.printHelpOn(System.out);
            System.exit(-1);
        } else if (spec != null) {
            final Action handler = spec.getValue();
            // hand everything off to vertx from here
            vertx.runOnContext((Void) -> {
                Promise.newInstance(vertx).then((context,onComplete) ->
                        initCassandra(success -> onComplete.accept(success)))
                .except(context -> System.out.println("failed, ex: " + context.getString(Promise.CONTEXT_FAILURE_KEY)))
                .done(context -> handler.callback())
                .eval();
            });
        } else {
            parser.printHelpOn(System.out);
            System.exit(0);
        }
    }

    /**
     * Entry point.
     */
    public static void main(String [] args) throws Exception {
        new Runner().run(args);

        // keep the app alive
        System.in.read();
    }
}
