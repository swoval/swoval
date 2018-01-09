#include "swoval_apple_file_system.hpp"
#include "node_api.h"

#include <stdio.h>
#include <execinfo.h>
#include <signal.h>
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

class handle_data {
    public:
    napi_ref callback_ref;
    napi_ref stop_stream_callback_ref;
    napi_env env;
    napi_async_work init_work;
    napi_async_work stop_stream_work;
    napi_async_work close_work;
    bool outstanding_callback = false;
    bool outstanding_stream_remove = false;
    ~handle_data() {
        NAPI(napi_delete_async_work(env, init_work));
        NAPI(napi_delete_async_work(env, stop_stream_work));
        NAPI(napi_delete_async_work(env, close_work));
    }
};

typedef handle<handle_data> NodeHandle;

static napi_value null_func(napi_env env, napi_callback_info info) { return nullptr; }
static void do_nothing(napi_env env, void *unused) {}
static void close_impl(napi_env env, napi_status async_status, void *data) {
    auto *h = static_cast<NodeHandle*>(data);
    if (h) {
        uint32_t ref_count;
        napi_ref cb_ref = h->data->callback_ref;
        napi_ref s_ref = h->data->stop_stream_callback_ref;

        h->close();
        NAPI(napi_reference_unref(env, cb_ref, &ref_count));
        assert(ref_count == 0);
        NAPI(napi_delete_reference(env, cb_ref));

        NAPI(napi_reference_unref(env, s_ref, &ref_count));
        assert(ref_count == 0);
        NAPI(napi_delete_reference(env, s_ref));
        if (h->data) {
            delete h->data;
            h->data = nullptr;
        }
        delete h;
    }
}

static void callback_impl(napi_env env, napi_status async_status, void *data) {
    auto *h = reinterpret_cast<NodeHandle*>(data);
    if (h->data) {
        h->data->outstanding_callback = false;
    }
    if (h->stopped) {
        if (!h->data->outstanding_stream_remove) {
            close_impl(env, napi_ok, data);
        }
        return;
    }
    napi_value callback;
    NAPI(napi_get_reference_value(h->data->env, h->data->callback_ref, &callback));
    napi_value args[2];

    for (size_t i = 0; i < h->events.size(); ++i) {
        std::string p = h->events[i].first;
        NAPI(napi_create_string_utf8(h->data->env, p.data(), p.length(), args));
        NAPI(napi_create_int32(h->data->env, h->events[i].second, args + 1));
        NAPI(napi_call_function(h->data->env, callback, callback, 2, args, nullptr));
    }
    h->events.clear();
}

static NodeHandle* get_handle_ptr(napi_env env, napi_value obj) {
    double res[0];
    NAPI(napi_get_value_double(env, obj, res));
    return *reinterpret_cast<NodeHandle**>(res);
}

static void queue_async(NodeHandle *h) {
    if (h->data && !h->stopped && !h->data->outstanding_callback) {
        h->data->outstanding_callback = true;
        NAPI(napi_queue_async_work(h->data->env, h->data->init_work));
    }
}

static void queue_async_stop_stream(NodeHandle *h, void *unused) {
    if (h->data && !h->stopped && !h->data->outstanding_stream_remove) {
        h->data->outstanding_stream_remove = true;
        NAPI(napi_queue_async_work(h->data->env, h->data->stop_stream_work));
    }
}

static void stop_stream_callback(napi_env env, napi_status async_status, void *data) {
    auto *h = static_cast<NodeHandle*>(data);
    if (h->data) h->data->outstanding_stream_remove = false;
    if (h->stopped || !h->data) {
        if (!h->data->outstanding_callback) {
            close_impl(env, napi_ok, data);
        }
        return;
    }
    napi_value callback;
    NAPI(napi_get_reference_value(h->data->env, h->data->stop_stream_callback_ref, &callback));
    napi_value args[1];

    for (size_t i = 0; i < h->stopped_streams.size(); ++i) {
        std::string *p = h->stopped_streams[i].get();
        NAPI(napi_create_string_utf8(h->data->env, p->data(), p->length(), args));
        NAPI(napi_call_function(h->data->env, callback, callback, 1, args, nullptr));
    }
    h->stopped_streams.clear();
}

napi_value close(napi_env env, napi_callback_info info) {
    NAPI_ARGS(1, "close");
    auto *h = get_handle_ptr(env, argv[0]);
    h->stopped = true;
    napi_value zero;
    NAPI(napi_create_int32(env, 0, &zero));
    NAPI(napi_set_named_property(env, argv[0], "l", zero));
    NAPI(napi_set_named_property(env, argv[0], "u", zero));
    if (!h->data->outstanding_callback && !h->data->outstanding_stream_remove) {
        close_impl(env, napi_ok, h);
    }
    return nullptr;
}

napi_value stop_stream(napi_env env, napi_callback_info info) {
    NAPI_ARGS(2, "stopStream");
    int32_t handle_key, stream_key;
    NAPI(napi_get_value_int32(env, argv[1], &stream_key));
    auto *h = get_handle_ptr(env, argv[0]);
    if (h) h->stopStream(stream_key, nullptr);
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
    auto *h = new NodeHandle(data, queue_async, queue_async_stop_stream);

    napi_value name;
    NAPI(napi_create_string_utf8(env, "callback", 8, &name));
    NAPI(napi_create_async_work(env, nullptr, name, do_nothing, callback_impl, h, &data->init_work));
    NAPI(napi_create_async_work(env, nullptr, name, do_nothing, stop_stream_callback, h, &data->stop_stream_work));
    NAPI(napi_create_async_work(env, nullptr, name, do_nothing, close_impl, h, &data->close_work));

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
