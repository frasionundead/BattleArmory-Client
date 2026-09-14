from pathlib import Path
p = Path('tools/decocraft-cache/src/com/battlearmory/decocraftcache/DecocraftCacheRuntime.java')
text = p.read_text(encoding='utf-8')
anchor = '        final ConcurrentHashMap<String, Object> resourceParsedModels = new ConcurrentHashMap<>();\n'
block = '''        final ConcurrentHashMap<String, Object> resourceParsedModels = new ConcurrentHashMap<>();
        final ConcurrentHashMap<WholeModelKey, Object[]> wholeBakedModels = new ConcurrentHashMap<>();
        volatile boolean wholeBakeInitialized;
        long wholeCacheHits;
        long wholeCacheMisses;
        Field wholeBakeryField;
        Field wholeSettingsField;
        Field wholeElementsField;
        Field wholeResolutionField;
        Field wholeSettingLocationField;
        Field wholeSettingScaleField;
        Field wholeSettingFlipVField;
        Field wholeSettingMaterialField;
        Field wholeElementNameField;
        Field wholeElementTypeField;
        Field wholeElementFacesField;
        volatile Field wholeFaceUvField;
        MethodHandle wholeBakeQuadHandle;
        MethodHandle resolveDirtyMaterialHandle;
        MethodHandle addUnculledFaceHandle;
        MethodHandle modelStateRotationHandle;
'''
if 'final ConcurrentHashMap<WholeModelKey, Object[]> wholeBakedModels' not in text:
    if anchor not in text:
        raise SystemExit('FamilyState resourceParsedModels anchor not found')
    text = text.replace(anchor, block, 1)
p.write_text(text, encoding='utf-8')
