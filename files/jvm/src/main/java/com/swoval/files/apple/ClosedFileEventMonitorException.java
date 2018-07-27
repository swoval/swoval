package com.swoval.files.apple;

import java.io.IOException;

/**
 * Exception that is thrown when the user attempts to create or stop a stream for a closed {@link
 * FileEventMonitor}.
 */
public class ClosedFileEventMonitorException extends IOException {}
