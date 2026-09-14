from pathlib import Path

runtime = Path('tools/decocraft-cache/src/com/battlearmory/decocraftcache/DecocraftCacheRuntime.java')
text = runtime.read_text(encoding='utf-8')

if 'import java.nio.file.Files;' not in text:
    text = text.replace(
        'import java.nio.charset.StandardCharsets;\n',
        'import java.nio.charset.StandardCharsets;\nimport java.nio.file.Files;\nimport java.nio.file.Path;\n',
        1)
if 'import java.util.concurrent.atomic.AtomicBoolean;' not in text:
    text = text.replace(
        'import java.util.concurrent.ConcurrentHashMap;\n',
        'import java.util.concurrent.ConcurrentHashMap;\nimport java.util.concurrent.atomic.AtomicBoolean;\nimport java.util.concurrent.atomic.AtomicLong;\n',
        1)

fields_anchor = '    private static volatile MethodHandle bakedQuadConstructor;\n\n'
fields = '''    private static volatile MethodHandle bakedQuadConstructor;

    // Temporary startup diagnostics for build 0013.  These counters tell us how
    // many quads Decocraft/Nature ask to bake versus how many unique quads survive
    // the content-addressed interner.  A daemon snapshot writer exits after the
    // bake count has been stable for ~10 seconds.
    private static final AtomicLong DECOCRAFT_BAKE_CALLS = new AtomicLong();
    private static final AtomicLong NATURE_BAKE_CALLS = new AtomicLong();
    private static final AtomicBoolean BAKE_STATS_THREAD_STARTED = new AtomicBoolean();

'''
if 'DECOCRAFT_BAKE_CALLS' not in text:
    if fields_anchor not in text:
        raise SystemExit('diagnostic field anchor not found')
    text = text.replace(fields_anchor, fields, 1)

method_anchor = '    /**\n     * Return one canonical immutable BakedQuad for identical baked vertex data.\n'
diag_methods = '''    public static void recordBakeCall(String family) {
        if ("decocraft_nature".equals(family)) {
            NATURE_BAKE_CALLS.incrementAndGet();
        } else if ("decocraft".equals(family)) {
            DECOCRAFT_BAKE_CALLS.incrementAndGet();
        }
        startBakeStatsWriter();
    }

    private static void startBakeStatsWriter() {
        if (!BAKE_STATS_THREAD_STARTED.compareAndSet(false, true)) return;
        Thread writer = new Thread(() -> {
            long previous = -1L;
            int stableIntervals = 0;
            try {
                while (stableIntervals < 5) {
                    Thread.sleep(2000L);
                    long current = DECOCRAFT_BAKE_CALLS.get() + NATURE_BAKE_CALLS.get();
                    writeBakeStatsSnapshot();
                    if (current == previous) stableIntervals++;
                    else stableIntervals = 0;
                    previous = current;
                }
                writeBakeStatsSnapshot();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                System.err.println("[BattleArmory][BakeStats] writer failed: " + t);
            }
        }, "BattleArmory-BakeStats");
        writer.setDaemon(true);
        writer.start();
    }

    private static void writeBakeStatsSnapshot() {
        long decocraft = DECOCRAFT_BAKE_CALLS.get();
        long nature = NATURE_BAKE_CALLS.get();
        int unique;
        long hits;
        long misses;
        boolean saturated;
        synchronized (QUAD_LOCK) {
            unique = quadSize;
            hits = quadHits;
            misses = quadMisses;
            saturated = quadInternerSaturated;
        }
        long calls = decocraft + nature;
        double hitRate = calls == 0L ? 0.0 : (100.0 * hits / calls);
        String stats = "Battle Armory bake diagnostics 0013\\n"
                + "decocraft_calls=" + decocraft + "\\n"
                + "decocraft_nature_calls=" + nature + "\\n"
                + "family_calls_total=" + calls + "\\n"
                + "interner_unique=" + unique + "\\n"
                + "interner_hits=" + hits + "\\n"
                + "interner_misses=" + misses + "\\n"
                + "interner_hit_rate_percent=" + String.format(java.util.Locale.ROOT, "%.2f", hitRate) + "\\n"
                + "interner_saturated=" + saturated + "\\n";
        try {
            Path out = Path.of(System.getProperty("user.dir"), "battlearmory-bake-stats.txt");
            Files.writeString(out, stats, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.err.println("[BattleArmory][BakeStats] snapshot write failed: " + t);
        }
        System.out.println("[BattleArmory][BakeStats] calls=" + calls + " decocraft=" + decocraft
                + " nature=" + nature + " unique=" + unique + " hits=" + hits + " misses=" + misses
                + " saturated=" + saturated);
    }

'''
if 'public static void recordBakeCall' not in text:
    if method_anchor not in text:
        raise SystemExit('diagnostic method anchor not found')
    text = text.replace(method_anchor, diag_methods + method_anchor, 1)

# Reset attribution counters with the resource-derived caches.
reset_anchor = '            resetQuadInterner();\n        }\n'
reset_repl = '''            resetQuadInterner();
        }
        DECOCRAFT_BAKE_CALLS.set(0L);
        NATURE_BAKE_CALLS.set(0L);
        BAKE_STATS_THREAD_STARTED.set(false);
'''
if 'DECOCRAFT_BAKE_CALLS.set(0L);' not in text:
    if reset_anchor not in text:
        raise SystemExit('diagnostic reset anchor not found')
    text = text.replace(reset_anchor, reset_repl, 1)

runtime.write_text(text, encoding='utf-8')

coremod = Path('tools/decocraft-cache/coremods/battlearmory_decocraft_cache.js')
js = coremod.read_text(encoding='utf-8')
needle = "            if (method === null) throw 'Battle Armory ' + f.id + ' quad dedupe: bakeQuad not found';\n\n"
inject = '''            if (method === null) throw 'Battle Armory ' + f.id + ' quad dedupe: bakeQuad not found';

            // Attribution only: count how many quads each Decocraft family asks to bake.
            var statsCode = new InsnList();
            statsCode.add(new LdcInsnNode(f.id));
            statsCode.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, HELPER, 'recordBakeCall',
                '(Ljava/lang/String;)V', false));
            method.instructions.insertBefore(method.instructions.getFirst(), statsCode);

'''
if "'recordBakeCall'" not in js:
    if needle not in js:
        raise SystemExit('coremod bake diagnostic anchor not found')
    js = js.replace(needle, inject, 1)
coremod.write_text(js, encoding='utf-8')

mods = Path('tools/decocraft-cache/META-INF/mods.toml')
mt = mods.read_text(encoding='utf-8')
if 'version="1.4.1"' not in mt:
    if 'version="1.4.0"' not in mt:
        raise SystemExit('mods.toml 1.4.0 version not found')
    mt = mt.replace('version="1.4.0"', 'version="1.4.1"', 1)
mods.write_text(mt, encoding='utf-8')
