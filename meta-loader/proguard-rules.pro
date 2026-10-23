-keep class org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub {
    public static byte[] dex;
    <init>();
}
# With --injectdex the app keeps its own dex files in this apk, so its R8 output and ours end up in
# one class loader with the app's dex first; classes left in the root package as La;..Li; would then
# resolve to the app's. The relocated ones are the vendored org.lsposed.hiddenapibypass.
-repackageclasses org.lsposed.external
-dontwarn androidx.annotation.NonNull
-dontwarn androidx.annotation.Nullable
-dontwarn androidx.annotation.VisibleForTesting
