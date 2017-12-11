#include "com_swoval_watcher_AppleFileSystemApi__.h"
#include <stdio.h>
#include <jni.h>
#include <CoreServices/CoreServices.h>

#define CALLBACK_SIG "(Ljava/lang/Object;)Ljava/lang/Object;"
#define EVENT_MODULE_SIG "Lcom/swoval/watcher/FileEvent$;"
#define EVENT_APPLY_SIG "(Ljava/lang/String;IJ)Lcom/swoval/watcher/FileEvent;"

static const CFStringRef mode = CFSTR("kCFRunLoopDefaultMode");

struct service_handle {
    CFRunLoopRef runLoop;
    jobject callback;
    jmethodID callbackApply;
    jobject fileEvent;
    jmethodID fileEventApply;
    JNIEnv *env;
};

static void callback(
        ConstFSEventStreamRef stream,
        void *info,
        size_t count,
        void *eventPaths,
        const FSEventStreamEventFlags flags[],
        const FSEventStreamEventId ids[])
{
    const char **paths = eventPaths;
    const struct service_handle *h = (struct service_handle*) info;
    JNIEnv *env = h->env;

    for (int i = 0; i < count; ++i) {
        jstring string = (*env)->NewStringUTF(env, paths[i]);
            jobject event = (*env)->CallStaticObjectMethod(
            env,
            h->fileEvent,
            h->fileEventApply,
            string,
            flags[i],
            ids[i]);
        (*env)->CallVoidMethod(env, h->callback, h->callbackApply, event);
    }
}

JNIEXPORT void JNICALL Java_com_swoval_watcher_AppleFileSystemApi_00024_close
(JNIEnv *env, jobject obj, jlong handle) {
    struct service_handle *h = (struct service_handle*) handle;
    CFRunLoopStop(h->runLoop);
    (*h->env)->DeleteGlobalRef(env, h->callback);
    (*h->env)->DeleteGlobalRef(env, h->fileEvent);
    free(h);
}

JNIEXPORT jlong JNICALL Java_com_swoval_watcher_AppleFileSystemApi_00024_init
(JNIEnv *env, jobject thread, jobject callback) {
    struct service_handle* handle= (struct service_handle*) malloc(sizeof(struct service_handle));
    jclass callbackClass = (*env)->GetObjectClass(env, callback);

    jclass eventClass = (*env)->FindClass(env, "com/swoval/watcher/FileEvent");

    handle->runLoop = CFRunLoopGetCurrent();
    handle->callback = (*env)->NewGlobalRef(env, callback);
    handle->callbackApply = (*env)->GetMethodID(env, callbackClass, "apply", CALLBACK_SIG);
    handle->fileEvent = (*env)->NewGlobalRef(env, eventClass);
    handle->fileEventApply = (*env)->GetStaticMethodID(env, eventClass, "apply", EVENT_APPLY_SIG);
    handle->env = env;

    return (jlong) handle;
}

JNIEXPORT void JNICALL Java_com_swoval_watcher_AppleFileSystemApi_00024_loop
(JNIEnv *env, jobject obj) {
    CFRunLoopRun();
}

JNIEXPORT jlong JNICALL Java_com_swoval_watcher_AppleFileSystemApi_00024_createStream
(JNIEnv *env, jobject object, jstring path, jdouble latency, jint flags, jlong handle) {
    const char *nativePath = (*env)->GetStringUTFChars(env, path, 0);
    CFStringRef mypath = CFStringCreateWithCStringNoCopy(NULL, nativePath, kCFStringEncodingUTF8, NULL);
    CFArrayRef pathsToWatch = CFArrayCreate(NULL, (const void **)&mypath, 1, NULL);
    FSEventStreamContext context = {0, (void *)handle, NULL, NULL, NULL};
    FSEventStreamRef stream = FSEventStreamCreate(
            NULL,
            callback,
            &context,
            pathsToWatch,
            kFSEventStreamEventIdSinceNow,
            latency,
            flags);
    FSEventStreamScheduleWithRunLoop(stream, ((struct service_handle*) handle)->runLoop, mode);
    FSEventStreamStart(stream);
    return (jlong) stream;
}

JNIEXPORT void JNICALL Java_com_swoval_watcher_AppleFileSystemApi_00024_stopStream
(JNIEnv *env, jobject object, jlong service_handle, jlong stream_handle) {
    if (stream_handle == 0) return;
    FSEventStreamRef stream = (FSEventStreamRef) stream_handle;
    CFRunLoopRef runLoop = ((struct service_handle*) service_handle)->runLoop;
    FSEventStreamStop(stream);
    FSEventStreamUnscheduleFromRunLoop(stream, runLoop, mode);
    FSEventStreamInvalidate(stream);
    FSEventStreamRelease(stream);
}
