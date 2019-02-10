package com.swoval.files;

import com.swoval.logging.Logger;

interface DebugLogger extends Logger {
  boolean shouldLog();
}
