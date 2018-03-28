#include <CoreServices/CoreServices.h>
#include <condition_variable>
#include <execinfo.h>
#include <iostream>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <thread>
#include "com_swoval_files_apple_FileEventsApi.h"
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

static void jni_callback(std::unique_ptr<Events> events, JNIHandle *h, Lock lock) {
    JNIEnv *env = h->data->env;
    if (!h->stopped) {
        for (auto e : *events) {
            jstring string = env->NewStringUTF(e.first.c_str());
            jobject event =
                env->NewObject(h->data->fileEvent, h->data->fileEventCons, string, e.second);
            env->CallVoidMethod(h->data->callback, h->data->callbackApply, event);
        }
    }
}

static void jni_stop_stream(std::unique_ptr<Strings> strings, JNIHandle *h, Lock lock) {
    JNIEnv *env = h->data->env;
    if (!h->stopped) {
        for (auto stream : *strings) {
            jstring string = env->NewStringUTF(stream.c_str());
            env->CallVoidMethod(h->data->pathCallback, h->data->pathCallbackApply, string);
        }
    }
}

JNIEXPORT void JNICALL Java_com_swoval_files_apple_FileEventsApi_stopLoop(JNIEnv *env, jclass clazz,
                                                                          jlong handle) {
    auto *h = reinterpret_cast<JNIHandle *>(handle);
    h->close();
}

JNIEXPORT void JNICALL Java_com_swoval_files_apple_FileEventsApi_close(JNIEnv *env, jclass clazz,
                                                                       jlong handle) {
    auto *h = reinterpret_cast<JNIHandle *>(handle);
    assert(h->stopped);
    env->DeleteGlobalRef(h->data->callback);
    env->DeleteGlobalRef(h->data->pathCallback);
    env->DeleteGlobalRef((jobject)h->data->fileEvent);
    Lock lock(h->mutex);
    delete h->data;
    lock.unlock();
    delete h;
}

JNIEXPORT void JNICALL Java_com_swoval_files_apple_FileEventsApi_loop(JNIEnv *env, jclass clazz,
                                                                      jlong handle) {
    auto *h = reinterpret_cast<JNIHandle *>(handle);
    loop(h);
}

JNIEXPORT jlong JNICALL Java_com_swoval_files_apple_FileEventsApi_init(JNIEnv *env, jclass thread,
                                                                       jobject callback,
                                                                       jobject pathCallback) {
    service_handle *handle   = new service_handle();
    jclass callbackClass     = env->GetObjectClass(callback);
    jclass pathCallbackClass = env->GetObjectClass(pathCallback);
    jclass eventClass        = env->FindClass(EVENT_SIG);

    env->GetJavaVM(&handle->jvm);
    handle->callback          = env->NewGlobalRef(callback);
    handle->callbackApply     = env->GetMethodID(callbackClass, "accept", CALLBACK_SIG);
    handle->pathCallback      = env->NewGlobalRef(pathCallback);
    handle->pathCallbackApply = env->GetMethodID(pathCallbackClass, "accept", CALLBACK_SIG);
    handle->fileEvent         = (jclass)env->NewGlobalRef(eventClass);
    handle->fileEventCons     = env->GetMethodID(eventClass, "<init>", EVENT_INIT_SIG);
    handle->env               = env;
    auto *h                   = new JNIHandle(handle, jni_callback, jni_stop_stream);

    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jint JNICALL Java_com_swoval_files_apple_FileEventsApi_createStream(
    JNIEnv *env, jclass clazz, jstring path, jdouble latency, jint flags, jlong handle) {
    auto *h = reinterpret_cast<JNIHandle *>(handle);
    return h->startStream(env->GetStringUTFChars(path, 0), latency, flags);
}

JNIEXPORT void JNICALL Java_com_swoval_files_apple_FileEventsApi_stopStream(JNIEnv *env,
                                                                            jclass clazz,
                                                                            jlong handle,
                                                                            jint stream_handle) {
    auto *h = reinterpret_cast<JNIHandle *>(handle);
    if (h)
        h->stopStream(stream_handle);
}
}
