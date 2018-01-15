package com.swoval.files.apple;

public interface Event {
    //https://developer.apple.com/documentation/coreservices/1455361-fseventstreameventflags
    int flags();

    default boolean isNone() {
        return flags() == 0;
    }

    default boolean mustScanSubDirs() {
        return (flags() & Flags.Event.MustScanSubDirs) != 0;
    }

    default boolean userDropped() {
        return (flags() & Flags.Event.UserDropped) != 0;
    }

    default boolean kernelDropped() {
        return (flags() & Flags.Event.KernelDropped) != 0;
    }

    default boolean eventIdsWrapped() {
        return (flags() & Flags.Event.EventIdsWrapped) != 0;
    }

    default boolean historyDone() {
        return (flags() & Flags.Event.HistoryDone) != 0;
    }

    default boolean rootChanged() {
        return (flags() & Flags.Event.RootChanged) != 0;
    }

    default boolean mount() {
        return (flags() & Flags.Event.Mount) != 0;
    }

    default boolean unmount() {
        return (flags() & Flags.Event.Unmount) != 0;
    }

    default boolean itemChangeOwner() {
        return (flags() & Flags.Event.ItemChangeOwner) != 0;
    }

    default boolean itemCreated() {
        return (flags() & Flags.Event.ItemCreated) != 0;
    }

    default boolean itemFinderInfoMod() {
        return (flags() & Flags.Event.ItemFinderInfoMod) != 0;
    }

    default boolean itemInodeMetaMod() {
        return (flags() & Flags.Event.ItemInodeMetaMod) != 0;
    }

    default boolean itemIsDir() {
        return (flags() & Flags.Event.ItemIsDir) != 0;
    }

    default boolean itemIsFile() {
        return (flags() & Flags.Event.ItemIsFile) != 0;
    }

    default boolean itemIsHardlink() {
        return (flags() & Flags.Event.ItemIsHardlink) != 0;
    }

    default boolean itemIsLastHardlink() {
        return (flags() & Flags.Event.ItemIsLastHardlink) != 0;
    }

    default boolean itemIsSymlink() {
        return (flags() & Flags.Event.ItemIsSymlink) != 0;
    }

    default boolean itemModified() {
        return (flags() & Flags.Event.ItemModified) != 0;
    }

    default boolean itemRemoved() {
        return (flags() & Flags.Event.ItemRemoved) != 0;
    }

    default boolean itemRenamed() {
        return (flags() & Flags.Event.ItemRenamed) != 0;
    }

    default boolean itemXattrMod() {
        return (flags() & Flags.Event.ItemXattrMod) != 0;
    }

    default boolean ownEvent() {
        return (flags() & Flags.Event.OwnEvent) != 0;
    }

    default boolean itemCloned() {
        return (flags() & Flags.Event.ItemCloned) != 0;
    }
}
