#include <CoreServices/CoreServices.h>
#include <condition_variable>
#include <execinfo.h>
#include <iostream>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <thread>
#include "com_swoval_files_apple_FileSystemApi.h"
#include "swoval_apple_file_system.hpp"

#define CALLBACK_SIG "(Ljava/lang/Object;)V"
#define PATH_CALLBACK_SIG "(Ljava/lang/Object;)V"
#define EVENT_SIG "com/swoval/files/apple/FileEvent"
#define EVENT_INIT_SIG "(Ljava/lang/String;I)V"

extern "C" {

struct service_handle {
    jobject callback;
    jmethodID callbackApply;
    jobject pathCallback;
    jmethodID pathCallbackApply;
    jclass fileEvent;
    jmethodID fileEventCons;
    JNIEnv *env;
    JavaVM *jvm;
};

typedef handle<service_handle> JNIHandle;

static void jni_callback(JNIHandle *h) {
    JNIEnv *env = h->data->env;
    if (!h->stopped) {
      for (int i = 0; i < h->events.size(); ++i) {
          jstring string = env->NewStringUTF(h->events[i].first.c_str());
          jobject event = env->NewObject(h->data->fileEvent, h->data->fileEventCons, string, h->events[i].second);
          env->CallVoidMethod(h->data->callback, h->data->callbackApply, event);
      };
      h->events.clear();
    }
}

static void jni_stop_stream(JNIHandle *h, void *data) {
    JNIEnv *env = data ? (JNIEnv *) data : h->data->env;
    if (!h->stopped) {
      for (int i = 0; i < h->stopped_streams.size(); ++i) {
          jstring string = env->NewStringUTF(h->stopped_streams[i]->c_str());
          env->CallVoidMethod(h->data->pathCallback, h->data->pathCallbackApply, string);
      }
      h->stopped_streams.clear();
    }
}

JNIEXPORT void JNICALL Java_com_swoval_files_apple_FileSystemApi_stopLoop
    (JNIEnv *env, jclass clazz, jlong handle) {
        auto *h = reinterpret_cast<JNIHandle*>(handle);
        h->close();
    }

JNIEXPORT void JNICALL Java_com_swoval_files_apple_FileSystemApi_close
    (JNIEnv *env, jclass clazz, jlong handle) {
    auto *h = reinterpret_cast<JNIHandle*>(handle);
    assert(h->stopped);
    env->DeleteGlobalRef(h->data->callback);
    env->DeleteGlobalRef(h->data->pathCallback);
    env->DeleteGlobalRef((jobject) h->data->fileEvent);
    delete h->data;
    delete h;
}

JNIEXPORT void JNICALL Java_com_swoval_files_apple_FileSystemApi_loop
  (JNIEnv *env, jclass clazz, jlong handle) {
  auto *h = reinterpret_cast<JNIHandle*>(handle);
  std::thread::id this_id = std::this_thread::get_id();
  loop(h);
}

JNIEXPORT jlong JNICALL Java_com_swoval_files_apple_FileSystemApi_init
    (JNIEnv *env, jclass thread, jobject callback, jobject pathCallback) {
        service_handle *handle = new service_handle();
        jclass callbackClass = env->GetObjectClass(callback);
        jclass pathCallbackClass = env->GetObjectClass(pathCallback);
        jclass eventClass = env->FindClass(EVENT_SIG);

        env->GetJavaVM(&handle->jvm);
        handle->callback = env->NewGlobalRef(callback);
        handle->callbackApply = env->GetMethodID(callbackClass, "accept", CALLBACK_SIG);
        handle->pathCallback = env->NewGlobalRef(pathCallback);
        handle->pathCallbackApply = env->GetMethodID(pathCallbackClass, "accept", CALLBACK_SIG);
        handle->fileEvent = (jclass) env->NewGlobalRef(eventClass);
        handle->fileEventCons = env->GetMethodID(eventClass, "<init>", EVENT_INIT_SIG);
        handle->env = env;
        auto *h = new JNIHandle(handle, nullptr, jni_callback, jni_stop_stream);

        return reinterpret_cast<jlong>(h);
    }

JNIEXPORT jint JNICALL Java_com_swoval_files_apple_FileSystemApi_createStream
(JNIEnv *env, jclass clazz, jstring path, jdouble latency, jint flags, jlong handle) {
    auto *h = reinterpret_cast<JNIHandle*>(handle);
    return h->startStream(env->GetStringUTFChars(path, 0), latency, flags);
}

JNIEXPORT void JNICALL Java_com_swoval_files_apple_FileSystemApi_stopStream
(JNIEnv *env, jclass clazz, jlong handle, jint stream_handle) {
    auto *h = reinterpret_cast<JNIHandle*>(handle);
    if (h) h->stopStream(stream_handle, env);
}
}
