package com.swoval.files.apple;

/**
 * The flags for creating a file event stream.
 *
 * @see <a
 *     href="https://developer.apple.com/documentation/coreservices/1455376-fseventstreamcreateflags"
 *     target="_blank">FSEventStreamCreateFlags</a>
 */
public class Flags {

  // The flags trip up code-gen if not in separate class.
  private static final class CreateFlags {
    public static final int None = 0;
    public static final int UseCFTypes = 0x00000001;
    public static final int NoDefer = 0x00000002;
    public static final int WatchRoot = 0x00000004;
    public static final int IgnoreSelf = 0x00000008;
    public static final int FileEvents = 0x00000010;
    public static final int MarkSelf = 0x00000020;
    public static final int UseExtendedData = 0x00000040;
  }

  /**
   * Wrapper around the apple file events FsEventStreamCreateFlags. Using this helper class avoids
   * directly having to use bit manipulation.
   */
  public static class Create {
    public final int value;

    public Create(final int value) {
      this.value = value;
    }

    public Create() {
      this(CreateFlags.None);
    }

    public int getValue() {
      return value;
    }

    public Create setUseCFTypes() {
      return new Create(value | CreateFlags.UseCFTypes);
    }

    public Create setNoDefer() {
      return new Create(value | CreateFlags.NoDefer);
    }

    public Create setWatchRoot() {
      return new Create(value | CreateFlags.WatchRoot);
    }

    public Create setIgnoreSelf() {
      return new Create(value | CreateFlags.IgnoreSelf);
    }

    public Create setFileEvents() {
      return new Create(value | CreateFlags.FileEvents);
    }

    public Create setMarkSelf() {
      return new Create(value | CreateFlags.MarkSelf);
    }

    public Create setUseExtendedData() {
      return new Create(value | CreateFlags.UseExtendedData);
    }

    public boolean hasUseCFTypes() {
      return (value & CreateFlags.UseCFTypes) > 0;
    }

    public boolean hasNoDefer() {
      return (value & CreateFlags.NoDefer) > 0;
    }

    public boolean hasWatchRoot() {
      return (value & CreateFlags.WatchRoot) > 0;
    }

    public boolean hasIgnoreSelf() {
      return (value & CreateFlags.IgnoreSelf) > 0;
    }

    public boolean hasFileEvents() {
      return (value & CreateFlags.FileEvents) > 0;
    }

    public boolean hasMarkSelf() {
      return (value & CreateFlags.MarkSelf) > 0;
    }

    public boolean hasUseExtendedData() {
      return (value & CreateFlags.UseExtendedData) > 0;
    }
  }

  /**
   * Wrapper around the file system event flags. The main purpose of this class is to avoid having
   * to directly manipulate the underlying integer containing the flag values.
   */
  public static class Event {
    public static final int None = 0;
    public static final int MustScanSubDirs = 0x00000001;
    public static final int UserDropped = 0x00000002;
    public static final int KernelDropped = 0x00000004;
    public static final int EventIdsWrapped = 0x00000008;
    public static final int HistoryDone = 0x00000010;
    public static final int RootChanged = 0x00000020;
    public static final int Mount = 0x00000040;
    public static final int Unmount = 0x00000080;
    public static final int ItemChangeOwner = 0x00004000;
    public static final int ItemCreated = 0x00000100;
    public static final int ItemFinderInfoMod = 0x00002000;
    public static final int ItemInodeMetaMod = 0x00000400;
    public static final int ItemIsDir = 0x00020000;
    public static final int ItemIsFile = 0x00010000;
    public static final int ItemIsHardlink = 0x00100000;
    public static final int ItemIsLastHardlink = 0x00200000;
    public static final int ItemIsSymlink = 0x00040000;
    public static final int ItemModified = 0x00001000;
    public static final int ItemRemoved = 0x00000200;
    public static final int ItemRenamed = 0x00000800;
    public static final int ItemXattrMod = 0x00008000;
    public static final int OwnEvent = 0x00080000;
    public static final int ItemCloned = 0x00400000;

    public static String flags(com.swoval.files.apple.Event flag) {
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
