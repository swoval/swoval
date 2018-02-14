package com.swoval.reflect;

import java.util.HashMap;
import java.util.Map;

public class CachingClassLoader extends ClassLoader {
    private Map<String, Class<?>> loaded = new HashMap<>();
    public CachingClassLoader(ClassLoader loader) {
        super(loader);
        System.out.println("OK LOADED CACHING");
    }
    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        Class<?> res = loaded.get(name);
        System.out.println("finding " + name + " " + res);
        if (res == null) {
            res = super.findClass(name);
            loaded.put(name, res);
        }
        return res;
    }
    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> res = loaded.get(name);
        //System.out.println("loading " + name + " " + res);
        if (res == null) {
            res = getParent().loadClass(name);
        }
        if (resolve) resolveClass(res);
        loaded.put(name, res);
        return res;
    }
    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        return loadClass(name, false);
    }

    Map<String, Class<?>> getLoadedClasses() {
        return loaded;
    }
}
