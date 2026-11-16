# From the libxposed/api README: keep module entry classes and let R8 rewrite the entry list.
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowobfuscation,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
