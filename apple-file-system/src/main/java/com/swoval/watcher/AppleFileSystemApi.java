package com.swoval.watcher;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.function.Consumer;
import java.nio.file.Files;
import java.nio.file.Path;

public class AppleFileSystemApi {
    private static final String NATIVE_LIBRARY = "sbt-apple-file-system0";

    private static final void exit(String msg) {
        System.out.println(msg);
        System.exit(1);
    }

    private static void loadPackaged() {
        try {
            String lib = System.mapLibraryName(NATIVE_LIBRARY);
            Path tmp = Files.createTempDirectory("jni-");
            String line = null;
            try {
                Process process = new ProcessBuilder("uname", "-sm").start();
                InputStream is = process.getInputStream();
                line = new BufferedReader(new InputStreamReader(is)).lines().findFirst().get();
                is.close();
            } catch (Exception e) {
                exit("Error running `uname` command");
            }
            String[] parts = line.split(" ");
            if (parts.length != 2) {
                exit("Could not determine platform: 'uname -sm' returned unexpected string: " + line);
            } else {
                String arch = parts[1].toLowerCase().replaceAll("\\s", "");
                String kernel = parts[0].toLowerCase().replaceAll("\\s", "");
                String plat = arch + "-" + kernel;
                String resourcePath = "/native/" + plat + "/" + lib;
                InputStream resourceStream = AppleFileSystemApi.class.getResourceAsStream(resourcePath);
                if (resourceStream == null) {
                    throw new UnsatisfiedLinkError(
                            "Native library " + lib + " (" + resourcePath + ") cannot be found on the classpath.");
                }

                Path extractedPath = tmp.resolve(lib);

                try {
                    Files.copy(resourceStream, extractedPath);
                } catch (Exception e) {
                    throw new UnsatisfiedLinkError("Error while extracting native library: " + e);
                }

                System.load(extractedPath.toAbsolutePath().toString());
            }
        } catch (Exception e) {
            exit("Couldn't load packaged library " + NATIVE_LIBRARY);
        }
    }

    static {
        try {
            System.loadLibrary(NATIVE_LIBRARY);
        } catch (UnsatisfiedLinkError e) {
            loadPackaged();
        }
    }

    public static native void close(long handle);

    public static native long init(Consumer<FileEvent> consumer);

    public static native void loop();

    public static native long createStream(String path, double latency, int flags, long handle);

    public static native void stopStream(long handle, long streamHandle);
}
