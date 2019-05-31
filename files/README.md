File tree views
===
This is a scala.js and jvm cross platform package that provides three important interfaces:
* PathWatcher: Monitor a set of paths for changes and run an arbitrary callback when changes
  are detected
* FileTreeView: list the contents of a directory more quickly than with the nio or io based implementations.
* FileTreeRepository: A reactive file system cache that keeps itself in sync with the file system and
  notifies the user when files are created/modified/deleted. It implements both the PathWatcher
  and the FileTreeView interfaces.

Javadocs may be found at
[files-jvm](https://swoval.github.io/docs/swoval/2.1.2/jvm/api/).
Scaladocs for scala.js may be found at
[files-js](https://swoval.github.io/docs/swoval/2.1.2/js/api/).
Not that most of the scala.js code is automatically generated from the java code so the javadocs
should be considered canonical.

The remainder of this readme will provide a brief overview of the three main classes along with
examples. Examples are provided in java 8 syntax to allow lambdas, but the library targets java 7.

Setup
==
The file tree views package is available on
[maven](https://mvnrepository.com/artifact/com.swoval/file-tree-views) and the latest version is
`2.1.2`. For sbt builds, it can be added with
`libraryDependencies += "com.swoval" % "file-tree-views" % "2.1.2"`.

FileTreeRepository
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
class FileTreeRepositoryExample {
  static void main(final String[] args) {
    try {
      final com.swoval.files.FileTreeRepository<Long> cache =
          com.swoval.files.FileTreeRepositories.get(
              true,
              new com.swoval.files.FileTreeDataViews.Converter<Long>() {
                @Override
                public Long apply(final TypedPath path) throws IOException {
                  return java.nio.file.Files.getLastModifiedTime(path.getPath()).toMillis();
                }
              });
      final com.swoval.files.FileTreeViews.CacheObserver<Long> cacheObserver =
          new com.swoval.files.FileTreeViews.CacheObserver<Long>() {
            @Override
            public void onCreate(final com.swoval.files.FileTreeDataViews.Entry<Long> newEntry) {
              System.out.println(
                  "Got event for new file: " + newEntry.getPath() +
                      " (last modified at " + newEntry.getValue() + ")");
            }

            @Override
            public void onDelete(final com.swoval.files.FileTreeDataViews.Entry<Long> oldEntry) {
              System.out.println(
                  "Got event for deleted file: " + oldEntry.getPath() +
                      " (last modified at " + oldEntry.getValue() + ")");
            }

            @Override
            public void onUpdate(
                final com.swoval.files.FileTreeDataViews.Entry<Long> oldEntry,
                final com.swoval.files.FileTreeDataViews.Entry<Long> newEntry) {
              System.out.println(
                  "Got event for updated file: " + newEntry.getPath() +
                      " (last modified at " + newEntry.getValue() + ")");
            }

            @Override
            public void onError(IOException exception) {}
          };
      final int observerHandle = cache.addCacheObserver(cacheObserver);
      try {
        final java.nio.file.Path path =
            java.nio.file.Paths.get(args.length == 0 ? "/tmp/foo" : args[0]);
        java.nio.file.Files.createFile(path);
        cache.register(path.getParent(), Integer.MAX_VALUE);
        java.nio.file.Files.setLastModifiedTime(
            path, java.nio.file.attribute.FileTime.fromMillis(3000));
        Thread.sleep(200); // Sleep long enough to ensure the callback fires
        for (final com.swoval.files.FileTreeDataViews.Entry<Long> entry :
            cache.listEntries(
                path.getParent(), Integer.MAX_VALUE, com.swoval.functional.Filters.AllPass)) {
          if (entry.getPath().equals(path)) {
            System.out.println("Listed an entry with last modified time " + entry.getValue());
          }
        }
        // The list above should print "Listed an entry with last modified time 3000"
      } finally {
        cache.close();
      }
    } catch (final IOException | InterruptedException e) {
      e.printStackTrace(System.err);
    }
  }
}
// "Got event for /tmp/foo" should be printed to the console.
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
Monitor a path for changes. This is lighter weight* than a FileTreeRepository if all that you are interested
in is being notified when a files is updated in some way. Unlike the 
[java.nio.file.WatchService](https://docs.oracle.com/javase/7/docs/api/java/nio/file/WatchService.html),
the PathWatcher supports recursive directory watching and the ability to monitor individual files.
When a file is created/modified/deleted, a user provided callback is fired. The callback is
provided an event object that specifies the modified path and makes a best guess at the type
of event. Due to some differences across platforms, it is difficult to correctly identify the type
of event and should not be trusted across all platforms. In practice, the event types should be
reasonably reliable on Windows and Linux while on OSX, creations may be misreported as modifications.
If this is important, use a FileCache or stat the file in the callback.

\* It doesn't maintain an in-memory cache of the file system. This cache can be quite large if the
paths under monitoring have many children.

Usage:
```java
public class PathWatcherExample {
  static void main(final String[] args) {
    try {
      final com.swoval.files.PathWatcher<com.swoval.files.PathWatchers.Event> watcher =
          com.swoval.files.PathWatchers.get(true);
      try {
        watcher.register(java.nio.file.Paths.get("/tmp/"), Integer.MAX_VALUE);
        java.nio.file.Files.createFile(java.nio.file.Paths.get("/tmp/foo"));
        Thread.sleep(1000); // ensure the callback fires
      } finally {
        watcher.close();
      }
    } catch (final IOException | InterruptedException e) {
      e.printStackTrace(System.err);
    }
  }
}
// "Got event for /tmp/foo/bar/baz" should be printed to the console.
```

Note that PathWatcher implements AutoCloseable. It's important to close it when you're done or
you will leak the background threads that monitor the file system and invoke callbacks.

FileTreeView
==

Recursively listing a directory can be surprisingly slow on the jvm. It turns out that this is
largely because none of the utilities in java.io.File or java.nio.file.Files take advantage of
some of the information available in the system apis for reading the contents of a directory. On
each of Mac OS, Linux and Windows there are system calls (readdir on Mac OS/Linux, findFirstFile on
Windows) that specify the type of file that is found in the directory. This is important because
without this information, it is necessary to stat the file, which is very expensive. For example,
using the native FileTreeView implementation, on a 2017 macbook pro with an SSD, it takes roughly
25 milliseconds to list a directory with 5000 subdirectories, each containing a single file. It
takes an additional 75 milliseconds to stat all of those files. By contrast, it takes roughly
110 milliseconds to list the directory using Files.walk or Files.walkFileTree.

The usage is simple. Suppose that there is a path
`/tmp/foo` containing a directory `buzz` and a regular file `baz`

```java
public class FileTreeViewExample {
  static void main(final String[] args) {
    try {
      for (final com.swoval.files.TypedPath file :
          com.swoval.files.FileTreeViews.list(
              java.nio.file.Paths.get("/tmp/foo"), 0, com.swoval.functional.Filters.AllPass)) {
        if (file.isDirectory()) System.out.println("Found directory: " + file);
        else System.out.println("Found file: " + file);
      }
    } catch (final IOException e) {
      e.printStackTrace(System.err);
    }
  }
}
```
should print (the order is unspecified)
```
Found directory: /foo/buzz
Found file: /foo/baz
```

The implementation uses the jni to directly make system calls. In the event that the native library
cannot be loaded, the implementation of FileTreeView can be overridden using the system parameter
`swoval.directory.lister`. To use a nio based implementation with no jni calls, start the jvm
with `-Dswoval.directory.lister=com.swoval.files.NioDirectoryLister`.

Note that this library is tested on Mac, Linux and Windows. That is also the order in which most
of the development work is done. This makes Windows the platform likely to have the most bugs.
Please report any that you find.

Javascript
==
There is also an npm module available [swoval-file-tree-views](https://www.npmjs.com/package/swoval_file_tree_views),
that exports a subset of the public api as documented in [files-jvm](https://swoval.github.io/docs/swoval/2.1.2/jvm/api).
In general, it should have very similar semantics except that all of the types in the public api
are javascript native types, i.e. String instead of java.nio.file.Path. The api documentation
can be found here:
[javascript public api](https://swoval.github.io/docs/swoval/2.1.2/js/api/com/swoval/files/node/index.html).

To use, add `"swoval_file_tree_views": "2.1.2"` to your package.json file.

A simple example:
```javascript
  const swoval = require("swoval_file_tree_views")
  const fs = require("fs")
  const repo = swoval.FileTreeRepositories.get(typedPath => fs.statSync(typedPath.getPath).mtimeMs)
  repo.addCacheObserver(
    new swoval.CacheObserver(
      e => console.log("creation " + e.getTypedPath.getPath + " " + e.getValue.getOrElse(0)),
      e => console.log("deletion " + e.getTypedPath.getPath + " " + e.getValue.getOrElse(0)),
      (old, e) => console.log("update " + e.getTypedPath.getPath + " " + e.getValue.getOrElse(0))
    )
  )
  const tmp = fs.realpathSync("/tmp")
  const foo = tmp + "/foo"
  fs.mkdirSync(foo)
  repo.register(tmp + "/foo")
  const bar = foo + "/bar.txt"
  fs.closeSync(fs.openSync(bar, "w"))
  {
    setTimeout(() => {
      repo.list(foo).forEach(f => console.log(f.getPath))
      fs.writeFileSync(bar, "bar")
      setTimeout(() => {
        fs.unlinkSync(bar)
        fs.rmdirSync(foo)
      }, 200)
    }, 200)
    undefined
  }
```
Running this in a node console (or as a source file), you should see console output like:
```
creation /private/tmp/foo/bar.txt 1537159860459.1897
update /private/tmp/foo/bar.txt 1537159860459.1897
/private/tmp/foo/bar.txt
update /private/tmp/foo/bar.txt 1537159860665.4883
deletion /private/tmp/foo 1537159860445.2976
deletion /private/tmp/foo/bar.txt 1537159860665.4883
```
