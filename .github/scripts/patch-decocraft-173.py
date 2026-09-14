from pathlib import Path

runtime_path = Path('tools/decocraft-cache/src/com/battlearmory/decocraftcache/DecocraftCacheRuntime.java')
coremod_path = Path('tools/decocraft-cache/coremods/battlearmory_decocraft_cache.js')
mods_path = Path('tools/decocraft-cache/META-INF/mods.toml')

runtime = runtime_path.read_text(encoding='utf-8')
coremod = coremod_path.read_text(encoding='utf-8')
mods = mods_path.read_text(encoding='utf-8')

# Safe follow-up to 1.7.1: do not touch Embeddium's mixin fields/object layout.
# The global quad interner only hit ~5.5% and saturated at 1,048,576 entries, while
# retaining several large parallel tables. Build quads directly instead.
needle = '''    private static Object newBakedQuad(int[] vertices, int tint, Object direction, Object sprite, boolean shade) {\n'''
if needle not in runtime:
    raise SystemExit('newBakedQuad marker missing')
helper = '''    public static Object uncachedBakedQuad(int[] vertices, int tint, Object direction, Object sprite, boolean shade) {\n        return newBakedQuad(vertices, tint, direction, sprite, shade);\n    }\n\n'''
if 'public static Object uncachedBakedQuad(' not in runtime:
    runtime = runtime.replace(needle, helper + needle, 1)

count = coremod.count("'canonicalBakedQuad'")
if count != 1:
    raise SystemExit(f'expected exactly 1 canonicalBakedQuad call site on 1.7.1 base, got {count}')
coremod = coremod.replace("'canonicalBakedQuad'", "'uncachedBakedQuad'", 1)

# The whole-model replay cache and rotation-base lookup are only needed while the
# resource bake is in progress. Once bake activity has been stable for ~10 seconds,
# every final BakedModel already owns the quad references it needs. Drop these
# duplicate reference graphs so they do not stay resident for the rest of the session.
old_tail = '''                writeWholeModelStats();\n            } catch (InterruptedException interrupted) {\n'''
new_tail = '''                writeWholeModelStats();\n                releaseBakeScratchCaches();\n            } catch (InterruptedException interrupted) {\n'''
if old_tail not in runtime:
    raise SystemExit('whole-model stats writer tail marker missing')
runtime = runtime.replace(old_tail, new_tail, 1)

marker = '''    private static void writeWholeModelStats() {\n'''
release = '''    private static void releaseBakeScratchCaches() {\n        long wholeEntries = 0L;\n        long rotationEntries = 0L;\n        for (FamilyState state : FAMILIES.values()) {\n            wholeEntries += state.wholeBakedModels.size();\n            rotationEntries += state.rotationBaseVertices.size();\n            state.wholeBakedModels.clear();\n            state.rotationBaseVertices.clear();\n        }\n        System.out.println("[BattleArmory][BakeScratch] released post-bake caches: wholeModels="\n                + wholeEntries + ", rotationBases=" + rotationEntries);\n    }\n\n'''
if marker not in runtime:
    raise SystemExit('writeWholeModelStats marker missing')
runtime = runtime.replace(marker, release + marker, 1)

if 'version="1.7.1"' not in mods:
    raise SystemExit('expected 1.7.1 base mods.toml')
mods = mods.replace('version="1.7.1"', 'version="1.7.3"', 1)

runtime_path.write_text(runtime, encoding='utf-8')
coremod_path.write_text(coremod, encoding='utf-8')
mods_path.write_text(mods, encoding='utf-8')
