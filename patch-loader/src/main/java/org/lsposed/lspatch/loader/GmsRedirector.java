package org.lsposed.lspatch.loader;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.util.Log;

import org.lsposed.lspatch.share.Constants;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class GmsRedirector {
    private static final String TAG = "LSPatch-GmsRedirect";
    private static final String REAL_GMS = Constants.REAL_GMS_PACKAGE_NAME;

    // 鎖定社群主流的 MicroG 套件名稱
    private static final String[] MICROG_PACKAGES = {
            "app.revanced.android.gms",   // ReVanced GmsCore (推薦)
            "org.microg.gms",             // Original MicroG
    };

    private static String targetGms = null;
    private static String originalSignature;

    public static void activate(Context context, String origSig) {
        originalSignature = origSig;

        targetGms = findInstalledMicroG(context);
        if (targetGms == null) {
            Log.w(TAG, "No MicroG/GmsCore found! GMS redirect disabled.");
            return;
        }

        Log.i(TAG, "Activating GMS redirect: " + REAL_GMS + " -> " + targetGms);

        hookIntentSetPackage();
        hookIntentSetComponent();
        hookIntentResolve();
        hookContentResolverAcquire();
        hookPackageManagerGetPackageInfo(context);

        Log.i(TAG, "GMS redirect hooks installed");
    }

    private static String findInstalledMicroG(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String pkg : MICROG_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return null;
    }

    private static String redirectPackage(String pkg) {
        if (REAL_GMS.equals(pkg) || "com.google.android.gsf".equals(pkg)) {
            return targetGms;
        }
        return null;
    }

    private static String redirectAuthority(String authority) {
        if (authority == null) return null;
        if (authority.startsWith(REAL_GMS + ".")) {
            return targetGms + authority.substring(REAL_GMS.length());
        }
        if (authority.equals(REAL_GMS)) {
            return targetGms;
        }
        if (authority.startsWith("com.google.android.gsf")) {
            return authority.replace("com.google.android.gsf", targetGms);
        }
        return null;
    }

    private static void hookIntentSetPackage() {
        try {
            XposedBridge.hookAllMethods(Intent.class, "setPackage", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String pkg = (String) param.args[0];
                    String redirected = redirectPackage(pkg);
                    if (redirected != null) param.args[0] = redirected;
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent.setPackage", t);
        }
    }

    private static void hookIntentSetComponent() {
        try {
            XposedBridge.hookAllMethods(Intent.class, "setComponent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    ComponentName cn = (ComponentName) param.args[0];
                    if (cn != null) {
                        String redirected = redirectPackage(cn.getPackageName());
                        if (redirected != null) {
                            param.args[0] = new ComponentName(redirected, cn.getClassName());
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent.setComponent", t);
        }
    }

    private static void hookIntentResolve() {
        try {
            XposedBridge.hookAllConstructors(Intent.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.thisObject;
                    ComponentName cn = intent.getComponent();
                    if (cn != null) {
                        String redirected = redirectPackage(cn.getPackageName());
                        if (redirected != null) {
                            intent.setComponent(new ComponentName(redirected, cn.getClassName()));
                        }
                    }
                    String pkg = intent.getPackage();
                    if (pkg != null) {
                        String redirected = redirectPackage(pkg);
                        if (redirected != null) {
                            intent.setPackage(redirected);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent constructors", t);
        }
    }

    private static void hookContentResolverAcquire() {
        try {
            for (String method : new String[]{
                    "acquireProvider", "acquireContentProviderClient",
                    "acquireUnstableProvider", "acquireUnstableContentProviderClient"
            }) {
                try {
                    XposedBridge.hookAllMethods(ContentResolver.class, method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[0] instanceof Uri) {
                                Uri uri = (Uri) param.args[0];
                                String newAuth = redirectAuthority(uri.getAuthority());
                                if (newAuth != null) {
                                    param.args[0] = uri.buildUpon().authority(newAuth).build();
                                }
                            } else if (param.args[0] instanceof String) {
                                String newAuth = redirectAuthority((String) param.args[0]);
                                if (newAuth != null) {
                                    param.args[0] = newAuth;
                                }
                            }
                        }
                    });
                } catch (Throwable ignored) {}
            }

            // 攔截 ContentResolver.call，遇到 SecurityException 則自動重試
            try {
                XposedBridge.hookAllMethods(ContentResolver.class, "call", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        for (int i = 0; i < param.args.length; i++) {
                            if (param.args[i] instanceof Uri) {
                                Uri uri = (Uri) param.args[i];
                                String newAuth = redirectAuthority(uri.getAuthority());
                                if (newAuth != null) {
                                    param.args[i] = uri.buildUpon().authority(newAuth).build();
                                }
                            } else if (param.args[i] instanceof String && i == 0) {
                                String newAuth = redirectAuthority((String) param.args[i]);
                                if (newAuth != null) {
                                    param.args[i] = newAuth;
                                }
                            }
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getThrowable() instanceof SecurityException) {
                            String msg = param.getThrowable().getMessage();
                            if (msg != null && (msg.contains("GoogleCertificatesRslt") ||
                                    msg.contains("not allowed") ||
                                    msg.contains("Access denied"))) {
                                Log.i(TAG, "GMS rejected call, retrying with MicroG");
                                for (int i = 0; i < param.args.length; i++) {
                                    if (param.args[i] instanceof Uri) {
                                        Uri uri = (Uri) param.args[i];
                                        String authority = uri.getAuthority();
                                        if (authority != null && authority.contains(REAL_GMS)) {
                                            param.args[i] = uri.buildUpon()
                                                    .authority(authority.replace(REAL_GMS, targetGms))
                                                    .build();
                                        }
                                    } else if (param.args[i] instanceof String && i == 0) {
                                        String s = (String) param.args[i];
                                        if (s.contains(REAL_GMS)) {
                                            param.args[i] = s.replace(REAL_GMS, targetGms);
                                        }
                                    }
                                }
                                param.setThrowable(null);
                                param.setResult(null);
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook ContentResolver", t);
        }
    }

    private static void hookPackageManagerGetPackageInfo(Context context) {
        try {
            XposedHelpers.findAndHookMethod(
                    context.getPackageManager().getClass(),
                    "getPackageInfo",
                    String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            PackageInfo pi = (PackageInfo) param.getResult();
                            if (pi != null && targetGms != null) {
                                if (targetGms.equals(pi.packageName) && (((int) param.args[1]) & PackageManager.GET_SIGNATURES) != 0) {
                                    if (originalSignature != null && !originalSignature.isEmpty()) {
                                        try {
                                            byte[] sigBytes = android.util.Base64.decode(originalSignature, android.util.Base64.DEFAULT);
                                            pi.signatures = new Signature[]{new Signature(sigBytes)};
                                        } catch (Throwable ignored) {}
                                    }
                                }
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook PackageManager.getPackageInfo", t);
        }
    }
}
