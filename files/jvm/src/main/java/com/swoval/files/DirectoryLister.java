package com.swoval.files;

import com.swoval.files.SimpleFileTreeView.ListResults;
import java.io.IOException;

interface DirectoryLister {
  ListResults apply(final String dir, final boolean followLinks) throws IOException;
}
