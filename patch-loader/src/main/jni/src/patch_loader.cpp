/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2022 LSPosed Contributors
 */

//
// Created by Nullptr on 2022/3/17.
//

#include "patch_loader.h"

#include "art/runtime/jit/profile_saver.h"
#include "art/runtime/oat_file_manager.h"
#include "core/native_api.h"
#include "elf/elf_image.h"
#include "elf/symbol_cache.h"
#include "jni/bypass_sig.h"
#include "jni/resources_hook.h"
#include "utils/jni_helper.hpp"

using namespace lsplant;
using namespace vector::native;

namespace lspd {

void PatchLoader::LoadDex(JNIEnv* env, Context::PreloadedDex&& dex) {
    auto class_class = JNI_FindClass(env, "java/lang/Class");
    auto stub =
        JNI_FindClass(env, "org/lsposed/lspatch/metaloader/LSPAppComponentFactoryStub");
    auto mid_get_classloader =
        JNI_GetMethodID(env, class_class, "getClassLoader", "()Ljava/lang/ClassLoader;");

    // Loading the AppComponentFactory can be re-entrant. In particular, some Android variants call
    // LoadedApk.getClassLoader() from an early hot-patch hook before ActivityThread has published
    // mBoundApplication.info. Reading the stub LoadedApk through ActivityThread at that point used a
    // null receiver in CallObjectMethod and CheckJNI aborted the process. JNI_OnLoad is invoked from
    // the already-defined factory class, so its defining loader is both available here and the exact
    // parent the in-memory framework loader needs.
    auto stub_classloader = JNI_CallObjectMethod(env, stub, mid_get_classloader);

    if (!stub_classloader) [[unlikely]] {
        LOGE("getStubClassLoader failed!!!");
        return;
    }

    auto in_memory_classloader = JNI_FindClass(env, "dalvik/system/InMemoryDexClassLoader");
    auto mid_init = JNI_GetMethodID(env, in_memory_classloader, "<init>",
                                    "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    auto byte_buffer_class = JNI_FindClass(env, "java/nio/ByteBuffer");
    auto dex_buffer = env->NewDirectByteBuffer(dex.data(), dex.size());

    // Give the framework loader a parent that already defines XResources' synthetic super
    // (xposed.dummy.XResourcesSuperClass, extending android.content.res.Resources). LSPatch loads
    // the framework into an already-running app, so there is no zygote window in which Vector could
    // generate that super first; if XResources is resolved with its super unreachable, ART records
    // the class as erroneous for the whole process and never retries. Installing the super loader as
    // the parent at construction keeps the super reachable the first time XResources is resolved.
    // The super is android.content.res.Resources: correct on stock Android; on ROMs whose Resources
    // is a custom subclass XResources cannot stand in for it and resource replacement stays inactive.
    ScopedLocalRef<jobject> super_loader{
        env, vector::native::jni::BuildDummySuperClassLoader(
                 env, stub_classloader.get(), "android.content.res.Resources",
                 "android.content.res.TypedArray")};
    jobject framework_parent = super_loader.get() ? super_loader.get() : stub_classloader.get();

    if (auto my_cl =
            JNI_NewObject(env, in_memory_classloader, mid_init, dex_buffer, framework_parent)) {
        inject_class_loader_ = JNI_NewGlobalRef(env, my_cl);
    } else {
        LOGE("InMemoryDexClassLoader creation failed!!!");
        return;
    }

    env->DeleteLocalRef(dex_buffer);
}

void PatchLoader::InitArtHooker(JNIEnv* env, const InitInfo& initInfo) {
    Context::InitArtHooker(env, initInfo);
    handler = initInfo;
    art::ProfileSaver::DisableInline(initInfo);
    art::FileManager::DisableBackgroundVerification(initInfo);
}

void PatchLoader::InitHooks(JNIEnv* env) {
    Context::InitHooks(env);
    RegisterBypass(env);
}

void PatchLoader::SetupEntryClass(JNIEnv* env) {
    if (auto entry_class =
            FindClassFromCurrentLoader(env, "org.lsposed.lspatch.loader.LSPApplication")) {
        entry_class_ = JNI_NewGlobalRef(env, entry_class);
    }
}

void PatchLoader::Load(JNIEnv* env) {
    lsplant::InitInfo initInfo{
        .inline_hooker =
            [](auto t, auto r) {
                void* bk = nullptr;
                return HookInline(t, r, &bk) == 0 ? bk : nullptr;
            },
        .inline_unhooker = [](auto t) { return UnhookInline(t) == 0; },
        .art_symbol_resolver =
            [](auto symbol) { return ElfSymbolCache::GetArt()->getSymbAddress(symbol); },
        .art_symbol_prefix_resolver =
            [](auto symbol) { return ElfSymbolCache::GetArt()->getSymbPrefixFirstAddress(symbol); },
        .generated_class_name = "LSPatch_",
        .generated_source_name = "Dobby",
    };

    auto stub = JNI_FindClass(env, "org/lsposed/lspatch/metaloader/LSPAppComponentFactoryStub");
    auto dex_field = JNI_GetStaticFieldID(env, stub, "dex", "[B");

    ScopedLocalRef<jbyteArray> array = JNI_GetStaticObjectField(env, stub, dex_field);
    jbyte* dex_bytes = env->GetByteArrayElements(array.get(), nullptr);
    auto dex = Context::PreloadedDex{dex_bytes, static_cast<size_t>(JNI_GetArrayLength(env, array))};

    InitArtHooker(env, initInfo);
    // LoadDex builds an InMemoryDexClassLoader that copies the buffer synchronously, so the bytes are
    // no longer needed once it returns; release them (JNI_ABORT: no copy-back of a read-only view).
    LoadDex(env, std::move(dex));
    env->ReleaseByteArrayElements(array.get(), dex_bytes, JNI_ABORT);
    InitHooks(env);

    SetupEntryClass(env);
    FindAndCall(env, "onLoad", "()V");
}
}  // namespace lspd
