#include <CoreServices/CoreServices.h>
#include <condition_variable>
#include <map>
#include <string>
#include <thread>
#include <vector>

namespace swoval {
static std::mutex id_mutex;
static int32_t current_id     = 0;
static const CFStringRef mode = CFSTR("kCFRunLoopDefaultMode");

template <class T> class handle;

template <class T> static void loop(handle<T> *h);

template <typename T> static void cleanupFunc(handle<T> *h);

typedef std::vector<std::pair<std::string, int32_t>> Events;
typedef std::vector<std::string> Strings;
typedef std::unique_lock<std::mutex> Lock;

template <typename T> class handle {
  public:
    T *data;
    CFRunLoopRef runLoop;
    CFRunLoopSourceRef sourceRef;
    CFRunLoopSourceContext *context = nullptr;
    std::mutex mutex;
    std::mutex runloop_mutex;
    std::condition_variable cond;
    std::map<int32_t, std::pair<std::string, FSEventStreamRef>> stream_handles;
    std::vector<int32_t> streams_to_remove;
    bool started = false;
    bool stopped = false;
    bool closed  = false;
    void (*callback)(std::unique_ptr<Events>, handle<T> *, Lock);
    Lock (*stop_stream_callback)(std::unique_ptr<Strings>, handle<T> *, Lock);

    handle(T *t, void (*cb)(std::unique_ptr<Events>, handle<T> *, Lock),
           Lock (*ss)(std::unique_ptr<Strings>, handle<T> *, Lock))
        : data(t), callback(cb), stop_stream_callback(ss) {}

    void close() {
        Lock lock(mutex);
        if (!started) {
            cond.wait(lock, [this] { return this->started; });
        }
        Lock runloopLock(runloop_mutex);
        if (!closed) {
            stopped = true;
            CFRunLoopSourceSignal(sourceRef);
            CFRunLoopWakeUp(runLoop);
        }
        runloopLock.unlock();
        if (!closed) {
            cond.wait(lock, [this] { return this->closed; });
        }
        lock.unlock();
    }

    void cleanupRunLoop(Lock runloopMutex) {
        CFRunLoopStop(runLoop);
        CFRunLoopSourceInvalidate(sourceRef);
        CFRunLoopRemoveSource(runLoop, sourceRef, mode);
        for (auto pair : stream_handles) {
            stop_stream(pair.second.second, runLoop);
        }
        stream_handles.clear();
        if (context) {
            delete context;
            context = nullptr;
        }
        closed = true;
    }

    int startStream(const char *path, double latency, int flags) {
        Lock lock(mutex);
        if (stopped) {
            fprintf(stderr, "Tried to add stream for %s on closed runloop.\n", path);
            return -1;
        }
        if (!started) {
            cond.wait(lock, [this] { return this->started; });
        }
        for (auto pair = stream_handles.begin(); pair != stream_handles.end(); ++pair) {
            if (!memcmp(pair->second.first.data(), path, pair->second.first.length())) {
                lock.unlock();
                return pair->first;
            }
        }

        CFStringRef mypath =
            CFStringCreateWithCStringNoCopy(nullptr, path, kCFStringEncodingUTF8, nullptr);
        CFArrayRef pathsToWatch      = CFArrayCreate(nullptr, (const void **)&mypath, 1, nullptr);
        FSEventStreamContext context = {0, this, nullptr, nullptr, nullptr};
        FSEventStreamRef stream =
            FSEventStreamCreate(nullptr, defaultCallback, &context, pathsToWatch,
                                kFSEventStreamEventIdSinceNow, latency, flags);
        FSEventStreamScheduleWithRunLoop(stream, runLoop, mode);
        if (!FSEventStreamStart(stream)) {
            return -1;
        }

        Lock id_lock(id_mutex);
        int32_t id         = current_id++;
        stream_handles[id] = std::make_pair(std::string(path), stream);
        lock.unlock();
        id_lock.unlock();
        CFRunLoopSourceSignal(sourceRef);
        CFRunLoopWakeUp(runLoop);
        return id;
    }

    void stopStream(int32_t stream_key) {
        Lock lock(mutex);
        if (!started) {
            cond.wait(lock, [this] { return this->started; });
        }
        if (!closed) {
            streams_to_remove.push_back(stream_key);
            CFRunLoopSourceSignal(sourceRef);
            CFRunLoopWakeUp(runLoop);
        }
    }
    Lock stopStream(int32_t stream_key, Lock lock) {
        if (stopped)
            return std::move(lock);
        auto strings = std::unique_ptr<Strings>(new Strings);
        auto pair    = stream_handles.find(stream_key);
        if (pair != stream_handles.end()) {
            stop_stream(pair->second.second, runLoop);
            strings->push_back(pair->second.first);
            stream_handles.erase(stream_key);
        }
        if (!stopped && stop_stream_callback) {
            lock = stop_stream_callback(std::move(strings), this, std::move(lock));
        }
        return std::move(lock);
    }

    static void defaultCallback(ConstFSEventStreamRef stream, void *info, size_t count,
                                void *eventPaths, const FSEventStreamEventFlags flags[],
                                const FSEventStreamEventId ids[]) {
        handle<T> *h = reinterpret_cast<handle<T> *>(info);
        Lock lock(h->runloop_mutex);
        if (h->stopped)
            return;
        const char **paths = reinterpret_cast<const char **>(eventPaths);
        auto events        = std::unique_ptr<Events>(new Events);
        for (size_t i = 0; i < count; ++i) {
            events->push_back(std::make_pair(std::string(paths[i]), flags[i]));
        }
        h->callback(std::move(events), h, std::move(lock));
    }

  private:
    void stop_stream(FSEventStreamRef stream, CFRunLoopRef runLoop) {
        FSEventStreamStop(stream);
        FSEventStreamUnscheduleFromRunLoop(stream, runLoop, mode);
        FSEventStreamInvalidate(stream);
        FSEventStreamRelease(stream);
    }
};

template <typename T> void loop(handle<T> *h) {
    h->runLoop          = CFRunLoopGetCurrent();
    h->context          = new CFRunLoopSourceContext();
    h->context->info    = h;
    h->context->perform = (void (*)(void *))cleanupFunc<T>;
    h->sourceRef        = CFRunLoopSourceCreate(nullptr, 0, h->context);
    CFRunLoopAddSource(h->runLoop, h->sourceRef, mode);
    Lock lock(h->mutex);
    Lock runloopLock(h->runloop_mutex);
    h->started = true;
    h->cond.notify_all();
    lock.unlock();
    runloopLock.unlock();
    CFRunLoopRun();
}

template <typename T> static void cleanupFunc(handle<T> *h) {
    std::vector<std::pair<int32_t, std::string>> streams;
    std::vector<int32_t> redundant_ids;
    Lock lock(h->mutex);
    Lock runloopLock(h->runloop_mutex);
    if (h->stopped) {
        h->cleanupRunLoop(std::move(runloopLock));
        h->cond.notify_all();
        lock.unlock();
        return;
    }
    for (auto pair = h->stream_handles.begin(); pair != h->stream_handles.end(); ++pair) {
        streams.push_back(make_pair(pair->first, pair->second.first));
    }
    for (size_t i = 0; i < streams.size(); ++i) {
        for (size_t j = i + 1; j < streams.size(); ++j) {
            std::string lpath = streams[i].second;
            std::string rpath = streams[j].second;
            if ((rpath.length() > lpath.length())) {
                if (!lpath.compare(0, lpath.length(), rpath, 0, lpath.length())) {
                    redundant_ids.push_back(streams[j].first);
                }
            } else if (!rpath.compare(0, rpath.length(), lpath, 0, rpath.length())) {
                redundant_ids.push_back(streams[i].first);
            }
        }
    }
    redundant_ids.insert(redundant_ids.end(), h->streams_to_remove.begin(),
                         h->streams_to_remove.end());
    h->streams_to_remove.clear();
    for (auto id : redundant_ids) {
        auto it = h->stream_handles.find(id);
        if (it != h->stream_handles.end()) {
            lock = h->stopStream(it->first, std::move(lock));
        }
    }
    h->cond.notify_all();
    lock.unlock();
}
}   // namespace swoval
