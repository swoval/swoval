Files
===
This is a scala.js and jvm cross platform package that provides three important classes:
* PathWatcher: Monitor a set of paths for changes and run an arbitrary callback when changes
  are detected
* FileCache: A reactive file system cache that keeps itself in sync with the file system and
  notifies the user when files are created/modified/deleted.
* QuickList: list the contents of a cachedDirectoryImpl more quickly than with the standard library

Javadocs may be found at [files-jvm](https://swoval.github.io/files/jvm).
Scaladocs for scala.js may be found at [files-js](https://swoval.github.io/files/js).
Not that most of the scala.js code is automatically generated from the java code so the javadocs
should be considered canonical.

The remainder of this readme will provide a brief overview of the three main classes along with
examples. Examples are provided in java 8 syntax to allow lambdas, but the library targets java 7.

FileCache
==
Maintains an in memory cache of some subset of the file system. Paths to watch are explicitly
registered with the cache. When a path under monitoring by the cache is created/modified/deleted,
the FileCache will detect the change and update its internal state. After the state is updated, it
will invoke user provided callbacks that notify the user of the change. The cache may be queried
for its contents at any time. The behavior of listing the cache is very similar to the ls tool.
The main difference is that the user may cache arbitrary data (of generic type `T`) about the path by specifying a
converter function from `Path` => `T`. When listing the cache, a Directory.Entry is returned
which provides access both to the underlying data as well as the cache value.

For example:
```java
final com.swoval.files.FileTreeRepository<Long> cache =
  com.swoval.files.PathWatchers.get(p -> Files.getLastModifiedTime(p).toMillis);
final com.swoval.files.CachedDirectoryImpl.Observer cacheObserver = com.swoval.files.Observers.apply(
    (e: com.swoval.files.CachedDirectoryImpl.Entry<Long>) -> System.out.println(
        "Got event for " + e.getPath() + " (last modified at " + e.getValue() + ")"));
final int observerHandle = cache.addObserver(cacheObserver);
try {
  final java.nio.file.Path path = Paths.get("/tmp/foo/bar/baz");
  java.nio.file.Files.createNewFile());
  cache.register(path.getParent());
  java.nio.file.Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(300));
  Thread.sleep(200); // Sleep long enough to ensure the callback fires
  for (final com.swoval.files.CachedDirectoryImpl.Entry<Long> entry : cache.list(path.getParent())) {
    if (entry.getPath().equals(path)) {
      System.out.println("Listed an entry with last modified time " + entry.getValue());
    }
  }
  // The list above should print "Listed and entry with last modified time 3000"
} finally {
  cache.close();
}
// "Got event for /tmp/foo/bar/baz" should be printed to the console.
```

For more details of the api, refer to the javadoc above. 

The motivation behind having the cache store a user specified value is that file system operations
can be quite expensive. Consider a tool for compiling source code. The tool may only want to
re-compile files whose contents have actually changed. Without a cache, every time the tool wants
to compile, it would have to recompute, say, the md5 sum of all of the source files. This can
be quite slow and can lead to the tool feeling sluggish for large projects. If, instead, the tool
can just refer to the cache, it can quickly determine which files have actually changed between
builds and not waste time scanning the file system and computing the md5 sums for all of these
files. Note that the api was designed specifically for the build tool use case, but would
likely be useful in many different application domains.

PathWatcher
==
Monitor a path for changes. This is lighter weight than a FileCache if all that you are interested
in is being notified when a files is updated in some way. Unlike the 
[java.nio.file.WatchService](https://docs.oracle.com/javase/7/docs/api/java/nio/file/WatchService.html),
the PathWatcher supports recursive cachedDirectoryImpl watching and the ability to monitor individual files.
When a file is created/modified/deleted, a user provided callback is fired. The callback is
provided an event object that specifies the modified path and makes a best guess at the type
of event. Due to some differences across platforms, it is difficult to correctly identify the type
of event and should not be trusted across all platforms. In practice, the event types should be
reasonably reliable on Windows and Linux while on OSX, creations may be misreported as modifications.
If this is important, use a FileCache or stat the file in the callback.

Usage:
```java
final com.swoval.files.PathWatcher watcher =
  com.swoval.files.PathWatchers.get(e -> System.out.println("Got event for " + e.getPath()));
try {
  watcher.register(Paths.get("/tmp/foo/bar"));
  java.nio.file.Files.createNewFile(Paths.get("/tmp/foo/bar/baz"));
} finally {
  watcher.close();
}
// "Got event for /tmp/foo/bar/baz" should be printed to the console.
```

Note that PathWatcher implements AutoCloseable. It's important to close it when you're done or
you will leak the background threads that monitor the file system and invoke callbacks.

QuickList
==

Recursively listing a cachedDirectoryImpl can be surprisingly slow on the jvm. It turns out that this is
largely because none of the utilities in java.io.File or java.nio.file.Files take advantage of
some of the information available in the system apis for reading the contents of a cachedDirectoryImpl. On
each of Mac OS, Linux and Windows there are system calls (readdir on Mac OS/Linux, findFirstFile on
Windows) that specify the type of file that is found in the cachedDirectoryImpl. This is important because
without this information, it is necessary to stat the file, which is very expensive. For example,
using QuickList, on a 2017 macbook pro with an SSD, it takes roughly 25 milliseconds to list a cachedDirectoryImpl
with 5000 subdirectories, each containing a single file. It takes an additional 75 milliseconds
to stat all of those files. By contrast, it takes roughly 110 milliseconds to list the cachedDirectoryImpl
using Files.walk or Files.walkFileTree. The usage is simple. Suppose that there is a path
`/tmp/foo/bar` containing a cachedDirectoryImpl `buzz` and a regular file `baz`

```java
for (final QuickFile file : com.swoval.files.FileTreeViews.list(Paths.get("/tmp/foo/bar"), 0) {
  if (file.isDirectory) System.out.println("Found cachedDirectoryImpl: " + file);
  else System.out.println("Found file: " + file);
}
```
should print (the order is unspecified)
```
Found cachedDirectoryImpl: /foo/bar/buzz
Found file: /foo/bar/baz
```

The implementation uses the jni to directly make system calls. In the event that the native library
cannot be loaded, the implementation of QuickList can be overridden using the system parameter
`swoval.cachedDirectoryImpl.lister`. To use a nio based implementation with no jni calls, start the jvm
with `-Dswoval.cachedDirectoryImpl.lister=com.swoval.files.NioDirectoryLister`. 

Note that this library is tested on Mac, Linux and Windows. That is also the order in which most
of the development work is done. This makes Windows the platform likely to have the most bugs.
Please report any that you find. 

