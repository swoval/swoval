package com.swoval.files.apple;

import static com.swoval.files.apple.Flags.Event.ItemCreated;
import static com.swoval.files.apple.Flags.Event.ItemInodeMetaMod;
import static com.swoval.files.apple.Flags.Event.ItemModified;
import static com.swoval.files.apple.Flags.Event.ItemRemoved;

public class FileEvent implements com.swoval.files.apple.Event {

    public final String fileName;
    private final int flags;

    public FileEvent(final String fileName, final int flags) {
        this.fileName = fileName;
        this.flags = flags;
    }

    private boolean hasFlags(int f) {
        return (flags & f) != 0;
    }

    public boolean isModified() {
        return hasFlags(ItemInodeMetaMod | ItemModified);
    }

    public boolean isNewFile() {
        return isCreated() && !isModified() && !isRemoved() && !isTouched();
    }

    public boolean isCreated() {
        return hasFlags(ItemCreated);
    }

    public boolean isRemoved() {
        return hasFlags(ItemRemoved);
    }

    public boolean isTouched() {
        return hasFlags(ItemInodeMetaMod);
    }

    @Override
    public String toString() {
        return "FileEvent(" + fileName + ", " + Flags.Event.flags(this) + ")";
    }

    @Override
    public int flags() {
        return flags;
    }
}

