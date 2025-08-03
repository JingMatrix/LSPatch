//
// Created by Milk on 4/9/21.
//

#ifndef VIRTUALM_VMCORE_H
#define VIRTUALM_VMCORE_H

#include <jni.h>
#include <sys/syscall.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/signal.h>
#include <sys/unistd.h>
#include <linux/prctl.h>
#include <sys/prctl.h>

#define VMCORE_CLASS "com/hello/sandbox/core/NativeCore"

class BoxCore {
 public:
  static JavaVM *getJavaVM();
  static int getApiLevel();
  static int getCallingUid(JNIEnv *env, int orig);
  static jstring redirectPathString(JNIEnv *env, jstring path);
  static jobject redirectPathFile(JNIEnv *env, jobject path);
  static jlongArray loadEmptyDex(JNIEnv *env);
};


#endif //VIRTUALM_VMCORE_H
