package com.swoval.watcher;

public class Flags {

    public static class Create {
        public final int value;

        public Create(final int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public Create setUseCFTypes() {
            return new Create(value | UseCFTypes);
        }
        public Create setNoDefer() {
            return new Create(value | NoDefer);
        }
        public Create setWatchRoot() {
            return new Create(value | WatchRoot);
        }
        public Create setIgnoreSelf() {
            return new Create(value | IgnoreSelf);
        }
        public Create setFileEvents() {
            return new Create(value | FileEvents);
        }
        public Create setMarkSelf() {
            return new Create(value | MarkSelf);
        }
        public Create useExtendedData() {
            return new Create(value | UseExtendedData);
        }
        public static final int  None = 0;
        public static final int  UseCFTypes = 0x00000001;
        public static final int  NoDefer = 0x00000002;
        public static final int  WatchRoot = 0x00000004;
        public static final int  IgnoreSelf = 0x00000008;
        public static final int  FileEvents = 0x00000010;
        public static final int  MarkSelf = 0x00000020;
        public static final int  UseExtendedData = 0x00000040;
    }

    public interface Event {
        //https://developer.apple.com/documentation/coreservices/1455361-fseventstreameventflags
        int flags();
        default boolean isNone() {
            return flags() == 0;
        }
        default boolean mustScanSubDirs() {
            return (flags() & MustScanSubDirs) != 0;
        }
        default boolean userDropped() {
            return (flags() & UserDropped) != 0;
        }
        default boolean kernelDropped() {
            return (flags() & KernelDropped) != 0;
        }
        default boolean eventIdsWrapped() {
            return (flags() & EventIdsWrapped) != 0;
        }
        default boolean historyDone() {
            return (flags() & HistoryDone) != 0;
        }
        default boolean rootChanged() {
            return (flags() & RootChanged) != 0;
        }
        default boolean mount() {
            return (flags() & Mount) != 0;
        }
        default boolean unmount() {
            return (flags() & Unmount) != 0;
        }
        default boolean itemChangeOwner() {
            return (flags() & ItemChangeOwner) != 0;
        }
        default boolean itemCreated() {
            return (flags() & ItemCreated) != 0;
        }
        default boolean itemFinderInfoMod() {
            return (flags() & ItemFinderInfoMod) != 0;
        }
        default boolean itemInodeMetaMod() {
            return (flags() & ItemInodeMetaMod) != 0;
        }
        default boolean itemIsDir() {
            return (flags() & ItemIsDir) != 0;
        }
        default boolean itemIsFile() {
            return (flags() & ItemIsFile) != 0;
        }
        default boolean itemIsHardlink() {
            return (flags() & ItemIsHardlink) != 0;
        }
        default boolean itemIsLastHardlink() {
            return (flags() & ItemIsLastHardlink) != 0;
        }
        default boolean itemIsSymlink() {
            return (flags() & ItemIsSymlink) != 0;
        }
        default boolean itemModified() {
            return (flags() & ItemModified) != 0;
        }
        default boolean itemRemoved() {
            return (flags() & ItemRemoved) != 0;
        }
        default boolean itemRenamed() {
            return (flags() & ItemRenamed) != 0;
        }
        default boolean itemXattrMod() {
            return (flags() & ItemXattrMod) != 0;
        }
        default boolean ownEvent() {
            return (flags() & OwnEvent) != 0;
        }
        default boolean itemCloned() {
            return (flags() & ItemCloned) != 0;
        }
        int  None = 0;
        int  MustScanSubDirs = 0x00000001;
        int  UserDropped = 0x00000002;
        int  KernelDropped = 0x00000004;
        int  EventIdsWrapped = 0x00000008;
        int  HistoryDone = 0x00000010;
        int  RootChanged = 0x00000020;
        int  Mount = 0x00000040;
        int  Unmount = 0x00000080;
        int  ItemChangeOwner = 0x00004000;
        int  ItemCreated = 0x00000100;
        int  ItemFinderInfoMod = 0x00002000;
        int  ItemInodeMetaMod = 0x00000400;
        int  ItemIsDir = 0x00020000;
        int  ItemIsFile = 0x00010000;
        int  ItemIsHardlink = 0x00100000;
        int  ItemIsLastHardlink = 0x00200000;
        int  ItemIsSymlink = 0x00040000;
        int  ItemModified = 0x00001000;
        int  ItemRemoved = 0x00000200;
        int  ItemRenamed = 0x00000800;
        int  ItemXattrMod = 0x00008000;
        int  OwnEvent = 0x00080000;
        int  ItemCloned = 0x00400000;
        static String flags(Event flag) {
            StringBuilder builder = new StringBuilder();
            builder.append("\n  mustScanSubDirs: " + flag.mustScanSubDirs());
            builder.append("\n  userDropped: " + flag.userDropped());
            builder.append("\n  kernelDropped: " + flag.kernelDropped());
            builder.append("\n  eventIdsWrapped: " + flag.eventIdsWrapped());
            builder.append("\n  historyDone: " + flag.historyDone());
            builder.append("\n  rootChanged: " + flag.rootChanged());
            builder.append("\n  mount: " + flag.mount());
            builder.append("\n  unmount: " + flag.unmount());
            builder.append("\n  itemChangeOwner: " + flag.itemChangeOwner());
            builder.append("\n  itemCreated: " + flag.itemCreated());
            builder.append("\n  itemFinderInfoMod: " + flag.itemFinderInfoMod());
            builder.append("\n  itemInodeMetaMod: " + flag.itemInodeMetaMod());
            builder.append("\n  itemIsDir: " + flag.itemIsDir());
            builder.append("\n  itemIsFile: " + flag.itemIsFile());
            builder.append("\n  itemIsHardlink: " + flag.itemIsHardlink());
            builder.append("\n  itemIsLastHardlink: " + flag.itemIsLastHardlink());
            builder.append("\n  itemIsSymlink: " + flag.itemIsSymlink());
            builder.append("\n  itemModified: " + flag.itemModified());
            builder.append("\n  itemRemoved: " + flag.itemRemoved());
            builder.append("\n  itemRenamed: " + flag.itemRenamed());
            builder.append("\n  itemXattrMod: " + flag.itemXattrMod());
            builder.append("\n  ownEvent: " + flag.ownEvent());
            builder.append("\n  itemCloned: " + flag.itemCloned());
            return "EventStreamFlags(\n" + builder.toString() + "\n)";
        }
    }
}
