#include <CoreServices/CoreServices.h>
#include <condition_variable>
#include <map>
#include <string>
#include <thread>
#include <vector>

static std::mutex id_mutex;
static int32_t current_id = 0;
static const CFStringRef mode = CFSTR("kCFRunLoopDefaultMode");

template<class T>
class handle;

template<class T>
static void loop(handle<T> *h);

template <typename T>
static void cleanupFunc(handle<T> *h);

template <typename T>
class handle {
    public:
    T *data;
    std::thread *loopThread = nullptr;
    CFRunLoopRef runLoop;
    CFRunLoopSourceRef sourceRef;
    CFRunLoopSourceContext *context = nullptr;
    std::mutex mutex;
    std::condition_variable cond;
    std::vector< std::pair<std::string, int32_t> > events;
    std::vector<std::unique_ptr<std::string>> stopped_streams;
    std::map<int32_t, std::pair< std::unique_ptr<std::string>, FSEventStreamRef> > stream_handles;
    bool started = false;
    bool stopped = false;
    bool closed = false;
    void (*callback)(handle<T> *) = nullptr;
    void (*stop_stream_callback)(handle<T> *, void*) = nullptr;

    handle(
        T *t,
        void (*looper)(handle<T> *),
        void (*cb)(handle<T> *),
        void (*ss)(handle<T> *, void*)
    ): data(t), callback(cb), stop_stream_callback(ss) {
        if (looper) loopThread = new std::thread(looper, this);
    }
    handle(T *t, void (*cb)(handle<T> *), void (*ss)(handle<T> *, void *))
        : handle(t, loop<T>, cb, ss)
    {
    }

    ~handle() {
    }

    void close() {
        std::unique_lock<std::mutex> lock(mutex);
        if (!closed) {
            stopped = true;
            closed = true;
            CFRunLoopSourceInvalidate(sourceRef);
            CFRunLoopRemoveSource(runLoop, sourceRef, mode);
            if (context) {
              delete context;
              context = nullptr;
            }
            for (auto& pair : stream_handles) {
                if (pair.second.second != nullptr) {
                    stop_stream(pair.second.second, runLoop);
                }
            }
            stream_handles.clear();
            lock.unlock();
            CFRunLoopStop(runLoop);
            if (loopThread) {
                if (loopThread->joinable()) {
                  loopThread->join();
                }
                delete loopThread;
            }
        } else {
            lock.unlock();
        }
    }

    int startStream(const char *path, double latency, int flags) {
        std::unique_lock<std::mutex> lock(mutex);
        if (stopped) {
          fprintf(stderr, "Tried to add stream for %s on closed runloop.", path);
          return -1;
        }
        if (!started) {
            cond.wait(lock, [this] { return this->started; });
        }
        for (auto pair = stream_handles.begin(); pair != stream_handles.end(); ++pair) {
            if (!memcmp(pair->second.first->data(), path, pair->second.first->length())) {
                return pair->first;
            }
        }
        lock.unlock();

        CFStringRef mypath = CFStringCreateWithCStringNoCopy(nullptr, path, kCFStringEncodingUTF8, nullptr);
        CFArrayRef pathsToWatch = CFArrayCreate(nullptr, (const void **)&mypath, 1, nullptr);
        FSEventStreamContext context = {0, this, nullptr, nullptr, nullptr};
        FSEventStreamRef stream = FSEventStreamCreate(
                nullptr,
                defaultCallback,
                &context,
                pathsToWatch,
                kFSEventStreamEventIdSinceNow,
                latency,
                flags);
        FSEventStreamScheduleWithRunLoop(stream, runLoop, mode);
        if (!FSEventStreamStart(stream)) {
          fprintf(stderr, "Error starting stream for path %s\n", path);
          return -1;
        }

        std::unique_lock<std::mutex> id_lock(id_mutex);
        int32_t id = current_id++;
        lock.lock();
        stream_handles[id] =
            std::make_pair(std::unique_ptr<std::string>(new std::string(path)), stream);
        lock.unlock();
        id_lock.unlock();
        CFRunLoopSourceSignal(sourceRef);
        CFRunLoopWakeUp(runLoop);
        return id;
    }

    void stopStream(int32_t stream_key, void *data, std::unique_lock<std::mutex> *l) {
        if (stopped) return;
        auto pair = stream_handles.find(stream_key);
        if (pair != stream_handles.end()) {
            stop_stream(pair->second.second, runLoop);
            if (pair->second.first) {
                stopped_streams.push_back(move(pair->second.first));
            }
            stream_handles.erase(stream_key);
        }
        if (!stopped && stop_stream_callback) {
            stop_stream_callback(this, data);
        }
    }
    void stopStream(int32_t stream_key, void *data) {
        std::unique_lock<std::mutex> lock(mutex);
        stopStream(stream_key, data, &lock);
        lock.unlock();
    }

    static void defaultCallback(
            ConstFSEventStreamRef stream,
            void *info,
            size_t count,
            void *eventPaths,
            const FSEventStreamEventFlags flags[],
            const FSEventStreamEventId ids[])
    {
        handle<T> *h = reinterpret_cast<handle<T>*>(info);
        std::unique_lock<std::mutex> lock(h->mutex);
        const char **paths = reinterpret_cast<const char **>(eventPaths);
        for (size_t i = 0; i < count; ++i) {
            h->events.push_back(std::make_pair(std::string(paths[i]), flags[i]));
        }
        h->callback(h);
    }

private:
    void stop_stream(FSEventStreamRef stream, CFRunLoopRef runLoop) {
        FSEventStreamStop(stream);
        FSEventStreamUnscheduleFromRunLoop(stream, runLoop, mode);
        FSEventStreamInvalidate(stream);
        FSEventStreamRelease(stream);
    }

};

template <typename T>
void loop(handle<T> *h) {
    h->runLoop = CFRunLoopGetCurrent();
    h->context = new CFRunLoopSourceContext();
    h->context->info = h;
    h->context->perform = (void(*)(void*))cleanupFunc<T>;
    h->sourceRef = CFRunLoopSourceCreate(nullptr, 0, h->context);
    CFRunLoopAddSource(h->runLoop, h->sourceRef, mode);
    std::unique_lock<std::mutex> lock(h->mutex);
    h->started = true;
    h->cond.notify_all();
    lock.unlock();
    CFRunLoopRun();
}

template <typename T>
static void cleanupFunc(handle<T> *h) {
    std::vector<std::pair<int32_t, std::string*>> streams;
    std::vector<int32_t> redundant_ids;
    std::unique_lock<std::mutex> lock(h->mutex);
    if (h->stopped) goto exit;
    for (auto pair = h->stream_handles.begin(); pair != h->stream_handles.end(); ++pair) {
        streams.push_back(make_pair(pair->first, pair->second.first.get()));
    }
    for (size_t i = 0; i < streams.size(); ++i) {
        for (size_t j = i + 1; j < streams.size(); ++j) {
            std::string lpath = *streams[i].second;
            std::string rpath = *streams[j].second;
            if ((rpath.length() > lpath.length())) {
                if (!lpath.compare(0, lpath.length(), rpath, 0, lpath.length())) {
                    redundant_ids.push_back(streams[j].first);
                }
            } else if (!rpath.compare(0, rpath.length(), lpath, 0, rpath.length())) {
                redundant_ids.push_back(streams[i].first);
            }
        }
    }
    for (auto id : redundant_ids) {
        auto it = h->stream_handles.find(id);
        if (it != h->stream_handles.end()) {
          h->stopStream(it->first, nullptr, &lock);
        }
    }
exit:
    lock.unlock();
}
