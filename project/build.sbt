sourcesInBase := true

// This allows us to put the *.scala build files in a package
// appropriate folder so that Intellij doesn't issue warnings.
sources in Compile ++= (baseDirectory.value / "com/swoval" ** "*.scala").get
