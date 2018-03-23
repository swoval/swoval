#include "swoval_apple_file_system.hpp"
#include "node_api.h"
#include "uv.h"

#include <stdlib.h>
#include <unistd.h>


#define MAX_PATH_SIZE 1024
#define NAPI(work) \
    do {\
        napi_status status; \
        status = work; \
        assert(status == napi_ok); \
    } while (0);

#define NAPI_ARGS(count, func) \
    size_t argc = count; \
    napi_value argv[count]; \
    NAPI(napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr)); \
    if (argc != count) { \
        return nullptr; \
    }

static void deleteHandle(uv_handle_t* handle) {
    delete handle;
}

class handle_data {
    public:
    napi_ref callback_ref;
    napi_ref stop_stream_callback_ref;
    napi_env env;
    napi_ref callback_name;
    uv_thread_t thread;
    std::atomic<int> ref_count;
    Events events;
    Strings streams;
    uv_async_t *async_work;
    ~handle_data() {
        uv_close((uv_handle_t*) async_work, deleteHandle);
    }
};

typedef handle<handle_data> NodeHandle;

static void close_impl(NodeHandle *h) {
    if (h && h->stopped) {
        uint32_t ref_count;
        napi_env env = h->data->env;
        napi_ref cb_ref = h->data->callback_ref;
        napi_ref s_ref = h->data->stop_stream_callback_ref;

        h->close();
        Lock lock(h->mutex);
        NAPI(napi_reference_unref(env, cb_ref, &ref_count));
        assert(ref_count == 0);
        NAPI(napi_delete_reference(env, cb_ref));

        NAPI(napi_reference_unref(env, s_ref, &ref_count));
        assert(ref_count == 0);
        NAPI(napi_delete_reference(env, s_ref));

        uv_thread_join(&h->data->thread);
        lock.unlock();
        uv_async_send(h->data->async_work);
    }
}

static NodeHandle* get_handle_ptr(napi_env env, napi_value obj) {
    double res[0];
    NAPI(napi_get_value_double(env, obj, res));
    return *reinterpret_cast<NodeHandle**>(res);
}

static void process_callback(uv_async_t *async) {
    auto *h = reinterpret_cast<NodeHandle*>(async->data);
    if (h->stopped) {
        if (h->closed) {
            delete h->data;
            delete h;
        }
        return;
    }
    napi_handle_scope scope;
    NAPI(napi_open_handle_scope(h->data->env, &scope));
    if (h->data->events.size()) {
        napi_value callback;
        NAPI(napi_get_reference_value(h->data->env, h->data->callback_ref, &callback));
        napi_value args[2];
        for (auto event : h->data->events) {
            std::string p = event.first;
            NAPI(napi_create_string_utf8(h->data->env, p.data(), p.length(), args));
            NAPI(napi_create_int32(h->data->env, event.second, args + 1));
            NAPI(napi_call_function(h->data->env, callback, callback, 2, args, nullptr));
        }
        h->data->events.clear();
    }
    if (h->data->streams.size()) {
        napi_value callback;
        NAPI(napi_get_reference_value(h->data->env, h->data->stop_stream_callback_ref, &callback));
        napi_value args[1];

        for (auto stream : h->data->streams) {
            NAPI(napi_create_string_utf8(h->data->env, stream.data(), stream.length(), args));
            NAPI(napi_call_function(h->data->env, callback, callback, 1, args, nullptr));
        }
        h->data->streams.clear();
    }
    NAPI(napi_close_handle_scope(h->data->env, scope));
}

static void enqueue_callback(std::unique_ptr<Events> events, NodeHandle *h, Lock lock) {
    for (auto event : *events) {
        h->data->events.push_back(event);
    }
    uv_async_send(h->data->async_work);
}

static void enqueue_stop_stream(std::unique_ptr<Strings> strings, NodeHandle *h, Lock lock) {
    for (auto stream : *strings) {
        h->data->streams.push_back(stream);
    }
    uv_async_send(h->data->async_work);
}

napi_value close(napi_env env, napi_callback_info info) {
    NAPI_ARGS(1, "close");
    auto *h = get_handle_ptr(env, argv[0]);
    h->stopped = true;
    napi_value zero;
    NAPI(napi_create_int32(env, 0, &zero));
    NAPI(napi_set_named_property(env, argv[0], "l", zero));
    NAPI(napi_set_named_property(env, argv[0], "u", zero));
    close_impl(h);
    return nullptr;
}

napi_value stop_stream(napi_env env, napi_callback_info info) {
    NAPI_ARGS(2, "stopStream");
    int32_t handle_key, stream_key;
    NAPI(napi_get_value_int32(env, argv[1], &stream_key));
    auto *h = get_handle_ptr(env, argv[0]);
    if (h) {
        h->stopStream(stream_key);
    }
    return nullptr;
}

napi_value start_stream(napi_env env, napi_callback_info info) {
    NAPI_ARGS(4, "startStream");
    double latency = 0.001;
    int flags = 0x2;
    int32_t handle = 0;
    char native_path[MAX_PATH_SIZE];
    size_t path_len;

    NAPI(napi_get_value_string_utf8(env, argv[0], native_path, MAX_PATH_SIZE, &path_len));

    NAPI(napi_get_value_double(env, argv[1], &latency));
    NAPI(napi_get_value_int32(env, argv[2], &flags));
    auto *h = get_handle_ptr(env, argv[3]);
    int id = h ? h->startStream(const_cast<const char*>(native_path), latency, flags) : -1;
    napi_value jid;
    NAPI(napi_create_int32(env, id, &jid));
    return jid;
}

napi_value initialize(napi_env env, napi_callback_info info) {
    NAPI_ARGS(2, "init");

    auto *data = new handle_data();
    data->env = env;
    NAPI(napi_create_reference(env, argv[0], 1, &data->callback_ref));
    NAPI(napi_create_reference(env, argv[1], 1, &data->stop_stream_callback_ref));
    auto *h = new NodeHandle(data, enqueue_callback, enqueue_stop_stream);

    h->data->async_work = new uv_async_t;
    h->data->async_work->data = h;
    uv_loop_t* uv_loop;
    NAPI(napi_get_uv_event_loop(env, &uv_loop));
    int res = 0;
    res = uv_async_init(uv_loop, h->data->async_work, process_callback);
    uv_thread_create(&h->data->thread, reinterpret_cast<void(*)(void*)>(loop<handle_data>), h);

    double *id = reinterpret_cast<double*>(&h);
    napi_value jid;
    napi_create_double(env, *id, &jid);
    return jid;
}

#define MODULE_ADD_FUNC(func, name) \
    do { \
        napi_value fn; \
        NAPI(napi_create_function(env, nullptr, 0, func, NULL, &fn)); \
        NAPI(napi_set_named_property(env, exports, name, fn)); \
    } while (0)

napi_value Init(napi_env env, napi_value exports) {
    MODULE_ADD_FUNC(initialize, "init");
    MODULE_ADD_FUNC(stop_stream, "stopStream");
    MODULE_ADD_FUNC(close, "close");
    MODULE_ADD_FUNC(start_stream, "createStream");
    return exports;
}

NAPI_MODULE(NODE_GYP_MODULE_NAME, Init);
