package com.swoval.watcher;

public class FileEvent implements Flags.Event {
    public final String fileName;
    public final int flags;
    public final long id;

    public FileEvent(String fileName, int flags, long id) {
        this.fileName = fileName;
        this.flags = flags;
        this.id = id;
    }

    private boolean hasFlags(int f) {
        return (flags & f) != 0;
    }
    public boolean isModified() {
        return hasFlags(ItemInodeMetaMod|ItemModified);
    }
    public boolean isNewFile() {
        return !isModified() && !isRemoved() && !isTouched() && hasFlags(ItemCreated);
    }
    public boolean isRemoved() {
        return hasFlags(ItemRemoved);
    }
    public boolean isTouched() {
        return hasFlags(ItemInodeMetaMod);
    }
    @Override
    public String toString() {
        return "FileEvent(" + fileName + ", " + id + ", " + Flags.Event.flags(this) + ")";
    }

    @Override
    public int flags() {
        return flags;
    }
}

