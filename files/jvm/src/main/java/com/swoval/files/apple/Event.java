package com.swoval.files.apple;

/**
 * Provides information about an event provided by the apple file events api. All of the public
 * methods are simple methods that check if the particular event flag is set in the flags variable
 * returned by the apple file event.
 *
 * @see <a
 *     href="https://developer.apple.com/documentation/coreservices/1455361-fseventstreameventflags"
 *     target="_blank">FSEventStreamEventFlags</a>
 */
abstract class Event {
  // https://developer.apple.com/documentation/coreservices/1455361-fseventstreameventflags
  abstract int flags();

  public boolean isNone() {
    return flags() == 0;
  }

  public boolean mustScanSubDirs() {
    return (flags() & Flags.Event.MustScanSubDirs) != 0;
  }

  public boolean userDropped() {
    return (flags() & Flags.Event.UserDropped) != 0;
  }

  public boolean kernelDropped() {
    return (flags() & Flags.Event.KernelDropped) != 0;
  }

  public boolean eventIdsWrapped() {
    return (flags() & Flags.Event.EventIdsWrapped) != 0;
  }

  public boolean historyDone() {
    return (flags() & Flags.Event.HistoryDone) != 0;
  }

  public boolean rootChanged() {
    return (flags() & Flags.Event.RootChanged) != 0;
  }

  public boolean mount() {
    return (flags() & Flags.Event.Mount) != 0;
  }

  public boolean unmount() {
    return (flags() & Flags.Event.Unmount) != 0;
  }

  public boolean itemChangeOwner() {
    return (flags() & Flags.Event.ItemChangeOwner) != 0;
  }

  public boolean itemCreated() {
    return (flags() & Flags.Event.ItemCreated) != 0;
  }

  public boolean itemFinderInfoMod() {
    return (flags() & Flags.Event.ItemFinderInfoMod) != 0;
  }

  public boolean itemInodeMetaMod() {
    return (flags() & Flags.Event.ItemInodeMetaMod) != 0;
  }

  public boolean itemIsDir() {
    return (flags() & Flags.Event.ItemIsDir) != 0;
  }

  public boolean itemIsFile() {
    return (flags() & Flags.Event.ItemIsFile) != 0;
  }

  public boolean itemIsHardlink() {
    return (flags() & Flags.Event.ItemIsHardlink) != 0;
  }

  public boolean itemIsLastHardlink() {
    return (flags() & Flags.Event.ItemIsLastHardlink) != 0;
  }

  public boolean itemIsSymlink() {
    return (flags() & Flags.Event.ItemIsSymlink) != 0;
  }

  public boolean itemModified() {
    return (flags() & Flags.Event.ItemModified) != 0;
  }

  public boolean itemRemoved() {
    return (flags() & Flags.Event.ItemRemoved) != 0;
  }

  public boolean itemRenamed() {
    return (flags() & Flags.Event.ItemRenamed) != 0;
  }

  public boolean itemXattrMod() {
    return (flags() & Flags.Event.ItemXattrMod) != 0;
  }

  public boolean ownEvent() {
    return (flags() & Flags.Event.OwnEvent) != 0;
  }

  public boolean itemCloned() {
    return (flags() & Flags.Event.ItemCloned) != 0;
  }
}
