sourcesInBase := true

// This allows us to put the *.scala build files in a package
// appropriate folder so that Intellij doesn't issue warnings.
Compile / unmanagedSourceDirectories += baseDirectory.value / "com"

libraryDependencies += "commons-codec" % "commons-codec" % "1.11"
