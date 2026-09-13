-keep class org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub {
    public static byte[] dex;
    <init>();
}
-repackageclasses org.lsposed.lspatch.metaloader
-dontwarn androidx.annotation.NonNull
-dontwarn androidx.annotation.Nullable
-dontwarn androidx.annotation.VisibleForTesting
