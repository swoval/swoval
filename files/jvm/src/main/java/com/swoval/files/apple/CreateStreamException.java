package com.swoval.files.apple;

import java.io.IOException;

/** Exception that is thrown when the system is unable to create a file event stream. */
public class CreateStreamException extends IOException {
  public CreateStreamException(final String path) {
    super(path);
  }
}
