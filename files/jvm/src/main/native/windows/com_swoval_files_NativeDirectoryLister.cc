#include <jni.h>
#include "windows.h"
#include "com_swoval_files_NativeDirectoryLister.h"

typedef struct Handle {
    WIN32_FIND_DATAW ffd;
    HANDLE handle;
    bool first = true;
    int err    = ERROR_SUCCESS;
} Handle;

extern "C" {
BOOL WINAPI DllMainCRTStartup(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    return TRUE;
}
/*
 * Class:     com_swoval_files_NativeDirectoryLister
 * Method:    errno
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_com_swoval_files_NativeDirectoryLister_errno(JNIEnv *, jobject,
                                                                         jlong handlep) {
    Handle *handle = (Handle *)handlep;
    int err        = handle->err;
    switch (err) {
    case ERROR_NO_MORE_FILES:
        return com_swoval_files_NativeDirectoryLister_EOF;
    case ERROR_SUCCESS:
        return com_swoval_files_NativeDirectoryLister_ESUCCESS;
    case ERROR_FILE_NOT_FOUND:
        return com_swoval_files_NativeDirectoryLister_ENOENT;
    case ERROR_ACCESS_DENIED:
        return com_swoval_files_NativeDirectoryLister_EACCES;
    case ERROR_PATH_NOT_FOUND:
        return com_swoval_files_NativeDirectoryLister_ENOENT;
    case ERROR_DIRECTORY:
        return com_swoval_files_NativeDirectoryLister_ENOTDIR;
    default:
        return err;
    }
}

/*
 * Class:     com_swoval_files_NativeDirectoryLister
 * Method:    strerror
 * Signature: (I)Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_com_swoval_files_NativeDirectoryLister_strerror(JNIEnv *env, jobject,
                                                                               jint err) {
    char buf[256];
    FormatMessage(FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS, NULL, err,
                  MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT), buf, (sizeof(buf) / sizeof(char)),
                  NULL);
    return env->NewStringUTF(buf);
}

/*
 * Class:     com_swoval_files_NativeDirectoryLister
 * Method:    openDir
 * Signature: (Ljava/lang/String;)J
 */
JNIEXPORT jlong JNICALL Java_com_swoval_files_NativeDirectoryLister_openDir(JNIEnv *env, jobject,
                                                                            jstring dir) {
    Handle *handle   = (Handle *)HeapAlloc(GetProcessHeap(), 0, sizeof(Handle));
    handle->first    = true;
    const jchar *str = env->GetStringChars(dir, 0);
    handle->handle   = FindFirstFileExW((LPCWSTR)str, FindExInfoBasic, &handle->ffd,
                                      FindExSearchNameMatch, NULL, FIND_FIRST_EX_LARGE_FETCH);
    if (handle->handle == INVALID_HANDLE_VALUE)
        handle->err = GetLastError();
    else
        handle->err = ERROR_SUCCESS;
    return (jlong)handle;
}

/*
 * Class:     com_swoval_files_NativeDirectoryLister
 * Method:    closeDir
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_com_swoval_files_NativeDirectoryLister_closeDir(JNIEnv *, jobject,
                                                                            jlong handle) {
    FindClose(((Handle *)handle)->handle);
    HeapFree(GetProcessHeap(), 0, (LPVOID)handle);
}

/*
 * Class:     com_swoval_files_NativeDirectoryLister
 * Method:    nextFile
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL Java_com_swoval_files_NativeDirectoryLister_nextFile(JNIEnv *, jobject,
                                                                             jlong h) {
    Handle *handle = (Handle *)h;
    if (handle->first) {
        handle->first = false;
    } else {
        if (!FindNextFileW(handle->handle, &handle->ffd)) {
            handle->err = GetLastError();
            return 0;
        }
    }
    return (jlong)&handle->ffd;
}

/*
 * Class:     com_swoval_files_NativeDirectoryLister
 * Method:    getType
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_com_swoval_files_NativeDirectoryLister_getType(JNIEnv *, jobject,
                                                                           jlong handle) {
    DWORD attrs = ((WIN32_FIND_DATA *)handle)->dwFileAttributes;
    if (attrs & FILE_ATTRIBUTE_REPARSE_POINT) {
        return com_swoval_files_NativeDirectoryLister_UNKNOWN;
    } else if (attrs & FILE_ATTRIBUTE_DIRECTORY) {
        return com_swoval_files_NativeDirectoryLister_DIRECTORY;
    } else {
        return com_swoval_files_NativeDirectoryLister_FILE;
    }
}

/*
 * Class:     com_swoval_files_NativeDirectoryLister
 * Method:    getName
 * Signature: (J)Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_com_swoval_files_NativeDirectoryLister_getName(JNIEnv *env, jobject,
                                                                              jlong handle) {
    WIN32_FIND_DATAW *data = (WIN32_FIND_DATAW *)handle;
    int len                = 0;
    for (len = 0; data->cFileName[len] != 0; len++) {
    }
    return env->NewString((const jchar *)data->cFileName, (jsize)len);
}
}
