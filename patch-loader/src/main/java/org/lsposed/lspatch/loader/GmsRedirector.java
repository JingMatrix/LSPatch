package org.lsposed.lspatch.loader;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.lsposed.lspatch.loader.util.XLog;
import org.lsposed.lspatch.share.Constants;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Points a patched app's Google Play services traffic at an installed replacement.
 *
 * A patched app is signed with LSPatch's key, so Google Play services rejects it wherever it checks the caller's
 * certificate -- most visibly at Google sign-in. A replacement answers the same requests under its own package, so
 * this rewrites the package and component of every GMS-bound intent, and the authority of every GMS-bound provider
 * call, to it. Which certificate that build then reports for the app is decided by the manifest metadata the
 * patcher wrote, not here.
 *
 * Actions follow what the replacement declares. One that kept Google's names answers Google's actions, so only its
 * package is rewritten. One that renamed its namespaces declares most actions only under its own name and a few
 * only under Google's, so each action is rewritten exactly when the renamed one resolves in it; see
 * {@link #redirectAction}.
 *
 * Nothing else is assumed about the build. Two kinds exist -- one that renamed Google's namespaces into its own so
 * it can sit beside Google Play services, and one that keeps Google's names because it is meant to replace it
 * outright -- and only the first needs its authorities and push names rewritten. That question is answered from the
 * package name alone, by {@link Constants#renamesGoogleNamespaces}, so no vendor is named here.
 *
 * An intent the app addresses to itself keeps Google's action names: the sign-in library opens its own
 * {@code SignInHubActivity} with Google's {@code GOOGLE_SIGN_IN} action, and that activity finishes on any other
 * action. Only Google's own package and {@code gsf} are matched for packages, so its package is never touched either.
 */
public class GmsRedirector {
    private static final String TAG = "LSPatch-GmsRedirect";
    private static final String REAL_GMS = Constants.REAL_GMS_PACKAGE_NAME;

    private static String targetGms = null;
    private static String selfPackage = null;
    private static PackageManager packageManager = null;

    /**
     * Points the app at [gmsReplacementPackage], if that build is installed and the app can see it.
     *
     * The package name is the whole configuration. Whether this build renamed Google's namespaces into its own,
     * and so whether its authorities and push names have to be rewritten to reach it, follows from the name alone;
     * nothing else about the build is assumed and no vendor is named here.
     */
    public static void activate(Context context, String gmsReplacementPackage) {
        if (!Constants.isGmsReplacement(gmsReplacementPackage)) {
            Log.w(TAG, "Not a usable replacement package, GMS redirect disabled: " + gmsReplacementPackage);
            return;
        }
        if (!isInstalled(context, gmsReplacementPackage)) {
            Log.w(TAG, gmsReplacementPackage + " is not installed or not visible to the app"
                    + " (add QUERY_ALL_PACKAGES), GMS redirect disabled");
            return;
        }
        targetGms = gmsReplacementPackage;
        selfPackage = context.getPackageName();
        packageManager = context.getPackageManager();

        Log.i(TAG, "Activating GMS redirect: " + REAL_GMS + " -> " + targetGms);
        setupC2dmRedirects();
        setupActionRedirects();

        hookIntentSetPackage();
        hookIntentSetAction();
        hookIntentGetAction();
        hookIntentSetComponent();
        hookIntentSetClass();
        hookIntentResolve();
        hookActivityStart();
        hookEgress();
        hookContentResolverAcquire();
        hookAuthTokens();

        Log.i(TAG, "GMS redirect hooks installed");
    }

    /** {@code ConnectionResult.SUCCESS}, named here so the app's own copy of the class is not needed. */
    private static final int PLAY_SERVICES_SUCCESS = 0;

    /** Announced once per process: the check runs constantly, and the reader needs telling only once. */
    private static boolean availabilityAnnounced = false;

    /**
     * Answers the client library's "is Google Play services here?" check on the replacement's behalf.
     *
     * That check is not an intent. It asks the package manager for Google's own package and inspects its
     * version and signing certificate, so the redirect never sees it, and on a device whose Google Play
     * services has been replaced rather than supplemented it fails -- whereupon the library stops before
     * sending anything. It does not merely report the failure: it hands back stub intents, so every API
     * behind it is refused even though the services it would have bound are present and answering.
     *
     * Only armed once the replacement has been found installed, so this reports something that is in fact
     * true: the APIs are available, from a different package. The first time it actually changes an answer it
     * is logged, because an app being told Google Play services is present when Google's own is absent is
     * worth being able to find afterwards.
     */
    static void answerAvailabilityChecks(ClassLoader appClassLoader) {
        if (targetGms == null || appClassLoader == null) return;
        // Four entry points, because the library answers the same question in several places and the APIs
        // behind them do not agree on which to ask: the sign-in client goes through GoogleApiAvailability,
        // the advertising-id client through GooglePlayServicesUtilLight. Hooking one leaves the others
        // refusing.
        for (String name : new String[]{
                "com.google.android.gms.common.GoogleApiAvailability",
                "com.google.android.gms.common.GoogleApiAvailabilityLight",
                "com.google.android.gms.common.GooglePlayServicesUtil",
                "com.google.android.gms.common.GooglePlayServicesUtilLight"}) {
            Class<?> clazz = XposedHelpers.findClassIfExists(name, appClassLoader);
            // Absent whenever the app does not ship the client library, which is the common case.
            if (clazz == null) continue;
            try {
                XposedBridge.hookAllMethods(clazz, "isGooglePlayServicesAvailable", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        if (!(result instanceof Integer)) return;
                        int reported = (Integer) result;
                        if (reported == PLAY_SERVICES_SUCCESS) return;
                        param.setResult(PLAY_SERVICES_SUCCESS);
                        announceAvailability(reported);
                    }
                });
                Log.i(TAG, "Answering availability checks on " + name);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to hook " + name + ".isGooglePlayServicesAvailable", t);
            }
        }
    }

    private static void announceAvailability(int reported) {
        synchronized (GmsRedirector.class) {
            if (availabilityAnnounced) return;
            availabilityAnnounced = true;
        }
        Log.i(TAG, "Reported Google Play services as available: the client answered " + reported
                + ", and the calls behind it go to " + targetGms);
    }

    private static boolean isInstalled(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * The package to use in place of [pkg], or null to leave it alone.
     *
     * {@code gsf} maps to the replacement unconditionally, unlike its authorities: a replacement serves the gsf
     * services from inside its own package, so an intent addressed to {@code com.google.android.gsf} reaches a
     * package that is not installed at all, whereas its *authorities* may well be registered under Google's names.
     * The two questions have different answers and are asked separately.
     */
    static String redirectPackage(String pkg) {
        if (REAL_GMS.equals(pkg) || GSF_PACKAGE.equals(pkg)) {
            return targetGms;
        }
        return null;
    }

    private static final String GSF_PACKAGE = "com.google.android.gsf";
    private static final String CHIMERA_AUTHORITY = REAL_GMS + ".chimera";

    /**
     * The authority to use in place of [authority], or null to leave it alone.
     *
     * Only a replacement that renamed its own namespaces serves these under its vendor; one that kept Google's
     * names registers them exactly as Google does, and rewriting them would address something nothing serves.
     * Chimera is left to Google in either case: no replacement provides Dynamite modules, so a rewrite there
     * only breaks a lookup that would otherwise be answered.
     */
    static String redirectAuthority(String authority) {
        if (authority == null) return null;
        if (CHIMERA_AUTHORITY.equals(authority)) return null;
        String vendor = Constants.gmsReplacementVendor(targetGms);
        if (vendor == null) return null;
        if (authority.equals(REAL_GMS)) {
            return targetGms;
        }
        if (authority.startsWith(REAL_GMS + ".")) {
            return targetGms + authority.substring(REAL_GMS.length());
        }
        // The gsf authorities keep their own segment under the replacement's vendor.
        if (authority.startsWith(GSF_PACKAGE)) {
            return vendor + authority.substring("com.google".length());
        }
        return null;
    }

    private static final String CHOOSE_ACCOUNT_ACTION =
            "com.google.android.gms.common.account.CHOOSE_ACCOUNT";
    private static final ComponentName SYSTEM_ACCOUNT_PICKER;
    static {
        ComponentName picker = null;
        try {
            picker = ComponentName.unflattenFromString("android/.accounts.ChooseTypeAndAccountActivity");
        } catch (Throwable ignored) {}
        SYSTEM_ACCOUNT_PICKER = picker;
    }

    /**
     * The namespace this replacement's push names live in, or null when it kept Google's.
     *
     * A prefix rather than a list of actions, so that the rule here is the same rule the patcher applied to the
     * manifest: it renames every {@code com.google.android.c2dm.*} string it finds, and anything this side left out
     * of a list would be an action rewritten in one place and not the other.
     */
    private static String c2dmPrefix = null;

    private static void setupC2dmRedirects() {
        c2dmPrefix = Constants.gmsReplacementC2dmPrefix(targetGms);
        if (c2dmPrefix != null) {
            Log.i(TAG, "C2DM names rewritten to " + c2dmPrefix + "*");
        } else {
            Log.i(TAG, "C2DM names left alone: " + targetGms + " keeps Google's C2DM namespace");
        }
    }

    static boolean isChooseAccountAction(String action) {
        return CHOOSE_ACCOUNT_ACTION.equals(action)
                || (targetGms != null && (targetGms + ".common.account.CHOOSE_ACCOUNT").equals(action));
    }

    private static void routeChooseAccountToSystem(Intent intent) {
        if (intent == null || SYSTEM_ACCOUNT_PICKER == null || !isChooseAccountAction(intent.getAction())) {
            return;
        }
        if (!SYSTEM_ACCOUNT_PICKER.equals(intent.getComponent()) || intent.getPackage() != null) {
            XLog.d(TAG, "Routing CHOOSE_ACCOUNT directly to the system account picker");
            intent.setComponent(SYSTEM_ACCOUNT_PICKER);
            intent.setPackage(null);
        }
    }

    private static final String GOOGLE_NAMESPACE = "com.google.android.";
    private static final String GMS_ACTION_PREFIX = REAL_GMS + ".";
    private static final String GSF_ACTION_PREFIX = GSF_PACKAGE + ".";

    /**
     * The namespace a replacement that renamed its own declares Google's actions in, or null when it kept
     * Google's and answers Google's names as they are.
     *
     * The vendor's own {@code android} namespace rather than its package: a renamed build renames every
     * {@code com.google.android.*} name it serves into {@code <vendor>.android.*}, which is the rule
     * {@link #redirectAuthority} already applies to its authorities. So the gms and gsf namespaces are rewritten
     * by one prefix and nothing else is -- an action outside them is not this build's to answer.
     */
    private static String renamedActionPrefix = null;

    /** Whether each renamed action resolves in the replacement, asked once per action. */
    private static final Map<String, Boolean> declaredActions = new ConcurrentHashMap<>();

    /**
     * Set while {@link #declares} asks the package manager, so nothing it builds on the way asks again.
     *
     * Defensive: nothing on that path constructs an intent of Google's today. A loop that appeared later would
     * not be a slow redirect but a stack overflow inside the app's own {@code setAction}, which is worth one
     * field to rule out.
     */
    private static final ThreadLocal<Boolean> querying = new ThreadLocal<>();

    private static void setupActionRedirects() {
        String vendor = Constants.gmsReplacementVendor(targetGms);
        renamedActionPrefix = vendor == null ? null : vendor + ".android.";
        if (renamedActionPrefix != null) {
            Log.i(TAG, "Google actions rewritten to " + renamedActionPrefix + "* where " + targetGms
                    + " declares them");
        }
    }

    /**
     * The action to use in place of [action], or null to leave it alone.
     *
     * Push names are rewritten wholesale, because the receiving side cannot be reached under Google's names at
     * all -- the permission guarding them is a signature permission of Google Play services that no other build
     * can hold -- which is why the patcher renames the app's own C2DM names in the manifest to match.
     *
     * Every other action in a namespace the replacement serves -- Google Play services' own and {@code gsf} --
     * is rewritten into the replacement's namespace only when the replacement declares it there. A replacement
     * that renamed its namespaces declares most actions only under its own name (sign-in among them) and a few
     * only under Google's, so neither leaving them all nor renaming them all reaches it; asking it is the only
     * rule that holds for every build. CHOOSE_ACCOUNT is left to the system account picker.
     */
    static String redirectAction(String action) {
        if (action == null) return null;
        if (c2dmPrefix != null && action.startsWith(Constants.GOOGLE_C2DM_PREFIX)) {
            return c2dmPrefix + action.substring(Constants.GOOGLE_C2DM_PREFIX.length());
        }
        if (renamedActionPrefix == null || !isGoogleServiceAction(action) || isChooseAccountAction(action)) {
            return null;
        }
        String renamed = renamedActionPrefix + action.substring(GOOGLE_NAMESPACE.length());
        return declares(renamed) ? renamed : null;
    }

    /**
     * Whether [action] names one of the services a replacement stands in for: Google Play services or gsf.
     *
     * The two are asked together because a replacement serves both from inside its own package -- the gsf
     * package is not installed at all -- so an app that reaches the login or checkin services by action needs
     * the same rewrite the gms actions get.
     */
    private static boolean isGoogleServiceAction(String action) {
        return action.startsWith(GMS_ACTION_PREFIX) || action.startsWith(GSF_ACTION_PREFIX);
    }

    /** The Google name for [action] if this rewrote it on the way out, or null when it is not one of ours. */
    static String restoreAction(String action) {
        if (action == null) return null;
        if (c2dmPrefix != null && action.startsWith(c2dmPrefix)) {
            return Constants.GOOGLE_C2DM_PREFIX + action.substring(c2dmPrefix.length());
        }
        return restoreGoogleAction(action);
    }

    /**
     * The Google name for an [action] this renamed into the replacement's namespace, or null when it renamed no
     * such action.
     *
     * A renamed push name starts with the same prefix, and is answered above before this is reached. Only an
     * action this asked the package manager about is in the map, so nothing else can be mistaken for one.
     */
    private static String restoreGoogleAction(String action) {
        if (action == null || renamedActionPrefix == null || !action.startsWith(renamedActionPrefix)) return null;
        if (!Boolean.TRUE.equals(declaredActions.get(action))) return null;
        return GOOGLE_NAMESPACE + action.substring(renamedActionPrefix.length());
    }

    /**
     * Whether [action] resolves to an activity, service or receiver of the replacement.
     *
     * A failed query counts as not declared, which leaves Google's name in place as a replacement that kept
     * Google's names would want.
     */
    private static boolean declares(String action) {
        Boolean known = declaredActions.get(action);
        if (known != null) return known;
        if (Boolean.TRUE.equals(querying.get())) return false;
        querying.set(true);
        boolean found;
        try {
            // Services first: the APIs behind these actions are bound rather than started, so the usual answer
            // is found in one call and the other two are never made.
            Intent query = new Intent(action).setPackage(targetGms);
            found = !packageManager.queryIntentServices(query, 0).isEmpty()
                    || !packageManager.queryIntentActivities(query, 0).isEmpty()
                    || !packageManager.queryBroadcastReceivers(query, 0).isEmpty();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to ask " + targetGms + " for " + action, t);
            found = false;
        } finally {
            querying.remove();
        }
        declaredActions.put(action, found);
        XLog.d(TAG, action + (found ? " is" : " is not") + " declared by " + targetGms);
        return found;
    }

    /**
     * Whether [intent] is addressed to the app itself, by package or by component.
     *
     * An intent carrying neither cannot be told from one meant for the replacement, so an implicit intent the app
     * sends under a Google action to a receiver it registered at run time keeps the renamed name. Only an action
     * the replacement declares is renamed at all, which is what keeps that narrow.
     */
    private static boolean isSelfTargeted(Intent intent) {
        if (selfPackage == null) return false;
        ComponentName cn = intent.getComponent();
        return selfPackage.equals(intent.getPackage()) || (cn != null && selfPackage.equals(cn.getPackageName()));
    }

    /**
     * Puts Google's action name back on an intent the app turns out to address to itself.
     *
     * The action is often set before the target, as in {@code new Intent(action).setClass(...)}, so it has
     * already been renamed by the time the target is known. Written to the field so the setter hook does not
     * rename it again.
     */
    private static void keepSelfAction(Intent intent) {
        if (renamedActionPrefix == null || !isSelfTargeted(intent)) return;
        String original = restoreGoogleAction((String) XposedHelpers.getObjectField(intent, "mAction"));
        if (original != null) XposedHelpers.setObjectField(intent, "mAction", original);
    }

    private static void hookIntentSetPackage() {
        try {
            XposedBridge.hookAllMethods(Intent.class, "setPackage", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.thisObject;
                    // Defense for the specific ordering of "setAction(CHOOSE_ACCOUNT)" before "setPackage(...)".
                    // The reverse sequence ("setPackage" before "setAction") is guarded by hookIntentSetAction's
                    // afterHookedMethod and final egress in hookActivityStart.
                    if (isChooseAccountAction(intent.getAction())) {
                        param.args[0] = null;
                        return;
                    }
                    String pkg = (String) param.args[0];
                    String redirected = redirectPackage(pkg);
                    if (redirected != null) param.args[0] = redirected;
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.thisObject;
                    routeChooseAccountToSystem(intent);
                    keepSelfAction(intent);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent.setPackage", t);
        }
    }

    private static void hookIntentSetAction() {
        try {
            XposedBridge.hookAllMethods(Intent.class, "setAction", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.thisObject;
                    String action = (String) param.args[0];
                    String redirected = redirectAction(action);
                    if (redirected != null) {
                        XLog.d(TAG, "Redirecting action in setAction: " + action + " -> " + redirected);
                        param.args[0] = redirected;
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.thisObject;
                    routeChooseAccountToSystem(intent);
                    keepSelfAction(intent);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent.setAction", t);
        }
    }

    private static void hookIntentGetAction() {
        try {
            XposedBridge.hookAllMethods(Intent.class, "getAction", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // On the hottest path this class hooks, so it is a prefix test and not a scan: the framework
                    // calls getAction on intents that have nothing to do with Google.
                    String restored = restoreAction((String) param.getResult());
                    if (restored != null) param.setResult(restored);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent.getAction", t);
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

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.thisObject;
                    routeChooseAccountToSystem(intent);
                    keepSelfAction(intent);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent.setComponent", t);
        }
    }

    /**
     * {@code setClass}, {@code setClassName} and {@code readFromParcel} assign the component without going through
     * {@code setComponent}, so the rewrite there never sees them and the component would keep pointing at Google's
     * package. {@code readFromParcel} is how an activity's own launch intent arrives; its action is the sender's,
     * so only the setters restore the app's own action names.
     */
    private static void hookIntentSetClass() {
        for (String method : new String[]{"setClass", "setClassName", "readFromParcel"}) {
            boolean outgoing = !method.equals("readFromParcel");
            try {
                XposedBridge.hookAllMethods(Intent.class, method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Intent intent = (Intent) param.thisObject;
                        if (outgoing) keepSelfAction(intent);
                        ComponentName cn = intent.getComponent();
                        if (cn == null) return;
                        String redirected = redirectPackage(cn.getPackageName());
                        if (redirected != null) {
                            XLog.d(TAG, "Redirecting component from " + cn.getClassName() + "'s package");
                            XposedHelpers.setObjectField(intent, "mComponent",
                                    new ComponentName(redirected, cn.getClassName()));
                        }
                    }
                });
            } catch (Throwable t) {
                Log.e(TAG, "Failed to hook Intent." + method, t);
            }
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
                    String action = intent.getAction();
                    if (action != null) {
                        String redirectedAction = redirectAction(action);
                        if (redirectedAction != null) {
                            intent.setAction(redirectedAction);
                        }
                    }
                    routeChooseAccountToSystem(intent);
                    keepSelfAction(intent);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent constructors", t);
        }
    }

    /**
     * Egress interceptor for direct startActivity / startActivityForResult invocations.
     * Note: Only covers direct caller invocations; indirect deliveries (e.g. wrapped in PendingIntent)
     * are not in this scope and rely on Intent constructor / setter hooks.
     *
     * {@link #hookEgress} reaches the same calls through {@code Instrumentation}, which is where both of these
     * end up in AOSP, and does more there. This stays because it does not depend on that being where they end
     * up: routing CHOOSE_ACCOUNT is what keeps the account picker from being asked of a build that cannot show
     * it, and {@code routeChooseAccountToSystem} is unchanged by being applied twice.
     */
    private static void hookActivityStart() {
        try {
            XC_MethodHook egressHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null) return;
                    for (Object arg : param.args) {
                        if (arg instanceof Intent) {
                            routeChooseAccountToSystem((Intent) arg);
                            break;
                        }
                    }
                }
            };
            XposedBridge.hookAllMethods(android.content.ContextWrapper.class, "startActivity", egressHook);
            XposedBridge.hookAllMethods(android.app.Activity.class, "startActivityForResult", egressHook);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook startActivity/startActivityForResult", t);
        }
    }

    private static final String CHIMERA_BOUND_SERVICE = "com.google.android.chimera.BoundService.START";
    private static final String CHIMERA_SCHEME = "chimera-action";

    /**
     * Rewrites every intent the framework is about to send, by field, applying all the rules at once.
     *
     * The setter hooks miss an intent whose constructor and setters were inlined into the app's own code by AOT
     * compilation, so the identity and phenotype bindings of such an app would still reach Google Play services.
     * {@code bindService}, {@code startService}, {@code startActivity} and the broadcast calls all end up here,
     * which is every way an app hands an intent to the framework short of a {@code PendingIntent} -- one the
     * framework sends on the app's behalf later, and whose intent the setter hooks have already seen.
     *
     * This is also the only place a chimera binding can be seen whole. A recent client library asks Google Play
     * services' chimera provider for the component implementing an API, then binds that component directly with
     * {@code BoundService.START} and the API's own action carried as {@code chimera-action:<action>}. A replacement
     * has no such components, so the binding is turned back into the API's own action, addressed to the
     * replacement's package.
     */
    private static void hookEgress() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null) return;
                for (Object arg : param.args) {
                    if (arg instanceof Intent) {
                        redirectOnEgress((Intent) arg);
                    } else if (arg instanceof Intent[]) {
                        for (Intent intent : (Intent[]) arg) {
                            if (intent != null) redirectOnEgress(intent);
                        }
                    }
                }
            }
        };
        try {
            Class<?> contextImpl = Class.forName("android.app.ContextImpl");
            for (String method : new String[]{
                    "bindServiceCommon", "startServiceCommon",
                    "sendBroadcast", "sendBroadcastAsUser", "sendOrderedBroadcast", "sendOrderedBroadcastAsUser"}) {
                try {
                    XposedBridge.hookAllMethods(contextImpl, method, hook);
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to hook ContextImpl." + method, t);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to reach ContextImpl", t);
        }
        try {
            XposedBridge.hookAllMethods(android.app.Instrumentation.class, "execStartActivity", hook);
            XposedBridge.hookAllMethods(android.app.Instrumentation.class, "execStartActivities", hook);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Instrumentation.execStartActivity", t);
        }
    }

    private static void redirectOnEgress(Intent intent) {
        unwrapChimera(intent);
        ComponentName cn = intent.getComponent();
        if (cn != null) {
            String redirected = redirectPackage(cn.getPackageName());
            if (redirected != null) {
                XposedHelpers.setObjectField(intent, "mComponent", new ComponentName(redirected, cn.getClassName()));
            }
        }
        String pkg = redirectPackage(intent.getPackage());
        if (pkg != null) XposedHelpers.setObjectField(intent, "mPackage", pkg);
        // Read from the field: getAction answers with Google's name for an action this already rewrote.
        String action = (String) XposedHelpers.getObjectField(intent, "mAction");
        if (!isSelfTargeted(intent)) {
            String redirected = redirectAction(action);
            if (redirected != null) {
                XLog.d(TAG, "Redirecting action on egress: " + action + " -> " + redirected);
                XposedHelpers.setObjectField(intent, "mAction", redirected);
            }
        } else {
            keepSelfAction(intent);
        }
        routeChooseAccountToSystem(intent);
    }

    private static void unwrapChimera(Intent intent) {
        if (!CHIMERA_BOUND_SERVICE.equals(XposedHelpers.getObjectField(intent, "mAction"))) return;
        ComponentName cn = intent.getComponent();
        if (cn == null || !(REAL_GMS.equals(cn.getPackageName()) || targetGms.equals(cn.getPackageName()))) return;
        Uri data = intent.getData();
        if (data == null || !CHIMERA_SCHEME.equals(data.getScheme())) return;
        String action = data.getSchemeSpecificPart();
        if (action == null || action.isEmpty()) return;
        XLog.d(TAG, "Unwrapping chimera binding of " + cn.getClassName() + " to " + action);
        XposedHelpers.setObjectField(intent, "mAction", action);
        XposedHelpers.setObjectField(intent, "mData", null);
        XposedHelpers.setObjectField(intent, "mComponent", null);
        XposedHelpers.setObjectField(intent, "mPackage", targetGms);
    }

    private static final String GOOGLE_ACCOUNT_TYPE = "com.google";

    /** Stands in {@link #targetAccountType} for "asked, and the replacement registers no authenticator". */
    private static final String NO_ACCOUNT_TYPE = "";

    /** The account type the replacement's authenticator registered, once asked. */
    private static volatile String targetAccountType = null;

    /**
     * Asks the replacement's account of the same name for auth tokens the app requests for a Google account.
     *
     * After sign-in, a client such as Cordova's Google sign-in plugin asks {@code AccountManager.getAuthToken}
     * for a token on the {@code com.google} account. That goes to Google Play services' authenticator, which
     * checks the caller's certificate and answers {@code UNREGISTERED_ON_API_CONSOLE}, so sign-in fails after it
     * has succeeded. A replacement that renamed its namespaces registers its authenticator under an account type
     * of its own and reports the original certificate, so the account is retyped to that one. A replacement that
     * registers none is left alone. Every {@code getAuthToken} overload, and {@code blockingGetAuthToken}, takes the
     * account first.
     *
     * {@code getAuthTokenByFeatures} and {@code invalidateAuthToken} name the account type instead of an
     * account, so they are followed separately: the first picks an account of that type itself, and the second
     * clears a token the authenticator cached. A token invalidated on Google's authenticator leaves the
     * replacement's copy in place, and the app retries with the same stale token it was told to throw away.
     */
    private static void hookAuthTokens() {
        XC_MethodHook accountHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length == 0 || !(param.args[0] instanceof Account)) return;
                Account account = (Account) param.args[0];
                if (!GOOGLE_ACCOUNT_TYPE.equals(account.type)) return;
                AccountManager accountManager = (AccountManager) param.thisObject;
                String type = targetAccountType(accountManager);
                if (type == null || !holdsAccount(accountManager, account.name, type)) return;
                XLog.d(TAG, "Asking " + targetGms + "'s " + type + " account for an auth token");
                param.args[0] = new Account(account.name, type);
            }
        };
        for (String method : new String[]{"getAuthToken", "blockingGetAuthToken"}) {
            try {
                XposedBridge.hookAllMethods(AccountManager.class, method, accountHook);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to hook AccountManager." + method, t);
            }
        }

        XC_MethodHook typeHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length == 0 || !GOOGLE_ACCOUNT_TYPE.equals(param.args[0])) return;
                String type = targetAccountType((AccountManager) param.thisObject);
                if (type == null) return;
                XLog.d(TAG, "Addressing " + targetGms + "'s " + type + " authenticator");
                param.args[0] = type;
            }
        };
        for (String method : new String[]{"getAuthTokenByFeatures", "invalidateAuthToken"}) {
            try {
                XposedBridge.hookAllMethods(AccountManager.class, method, typeHook);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to hook AccountManager." + method, t);
            }
        }
    }

    /**
     * The account type the replacement's authenticator registered, or null when it registered none.
     *
     * Asked once, "none" remembered as well: a build that registers no authenticator is exactly the one whose
     * answer would otherwise be asked again on every token request. A query that fails counts as none, as in
     * {@link #declares}, which leaves the app asking Google's authenticator as it did before. The first
     * authenticator the replacement registers is the one used, and the log names it.
     */
    private static String targetAccountType(AccountManager accountManager) {
        String type = targetAccountType;
        if (type == null) {
            type = NO_ACCOUNT_TYPE;
            try {
                for (AuthenticatorDescription authenticator : accountManager.getAuthenticatorTypes()) {
                    if (targetGms.equals(authenticator.packageName)) {
                        type = authenticator.type;
                        break;
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to ask " + targetGms + " which account type it registers", t);
            }
            targetAccountType = type;
            Log.i(TAG, NO_ACCOUNT_TYPE.equals(type)
                    ? targetGms + " registers no authenticator, so Google accounts are left alone"
                    : "Google accounts are retyped to " + type + " for auth tokens");
        }
        return NO_ACCOUNT_TYPE.equals(type) ? null : type;
    }

    /**
     * Whether the replacement may hold an account of [type] named [name].
     *
     * Retyping to an account the replacement demonstrably does not hold would trade Google's "unregistered"
     * answer for an authenticator failure, so a list that holds other accounts of the type and not this one
     * stops the retype.
     *
     * Anything less certain answers yes. An empty list is not an absent account: since API 26 an app is shown
     * only the accounts whose authenticator made them visible to it, while a token request for an account it
     * cannot list still reaches that authenticator -- and below API 26 listing at all needs {@code GET_ACCOUNTS},
     * which the app may not hold. Where this cannot see, the authenticator is left to accept or refuse the
     * request as it would have anyway.
     */
    private static boolean holdsAccount(AccountManager accountManager, String name, String type) {
        try {
            Account[] accounts = accountManager.getAccountsByType(type);
            if (accounts.length == 0) return true;
            for (Account account : accounts) {
                if (name.equals(account.name)) return true;
            }
            XLog.d(TAG, targetGms + " holds " + type + " accounts, but none under this name");
            return false;
        } catch (Throwable t) {
            XLog.d(TAG, "Cannot list " + type + " accounts, so asking anyway: " + t);
            return true;
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
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook ContentResolver", t);
        }
    }
}
